package dev.lattante.commerceflow.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lattante.commerceflow.identity.*;
import dev.lattante.commerceflow.shared.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ProductAdministration {
  public record Snapshot(
      String name,
      BigDecimal amount,
      Money.Currency currency,
      boolean active,
      int onHand,
      int reserved,
      long version) {}

  public record ProductView(String id, String sku, Snapshot state) {}

  public record AuditEntry(
      String id,
      String actorId,
      String reason,
      Instant occurredAt,
      Snapshot before,
      Snapshot after) {}

  public record AuditPage(List<AuditEntry> content, int page, int size, long totalElements) {}

  private final ProductRepository products;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;

  public ProductAdministration(
      ProductRepository products, JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
    this.products = products;
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ProductView find(String id, Actor actor) {
    admin(actor);
    return view(products.findById(id).orElseThrow(NotFound::new));
  }

  @Transactional(readOnly = true)
  public CatalogService.PageView<ProductView> list(
      Actor actor, Boolean active, int page, int size) {
    admin(actor);
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new DomainViolation("Page must be 0..10000 and size 1..100");
    org.springframework.data.jpa.domain.Specification<Product> filter =
        (root, q, cb) -> active == null ? cb.conjunction() : cb.equal(root.get("active"), active);
    var result =
        products.findAll(
            filter,
            org.springframework.data.domain.PageRequest.of(
                page, size, org.springframework.data.domain.Sort.by("sku")));
    return new CatalogService.PageView<>(
        result.map(this::view).getContent(), page, size, result.getTotalElements());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProductView update(String id, Actor actor, ProductEdit edit) {
    admin(actor);
    Objects.requireNonNull(edit);
    Product product = products.lockById(id).orElseThrow(NotFound::new);
    Snapshot before = snapshot(product);
    if (!product.revise(edit)) return view(product);
    // Flush first: Hibernate increments the same @Version used by reservations and payments.
    // The following audit insert must succeed before the transaction can commit.
    products.flush();
    Snapshot after = snapshot(product);
    jdbc.update(
        """
        INSERT INTO product_change_audit
          (id,product_id,actor_id,product_version,reason,occurred_at,before_state,after_state)
        VALUES(?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID().toString(),
        id,
        actor.id(),
        after.version(),
        edit.reason(),
        Timestamp.from(clock.instant()),
        encode(before),
        encode(after));
    return view(product);
  }

  @Transactional(readOnly = true)
  public AuditPage audit(String id, Actor actor, int page, int size) {
    admin(actor);
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new DomainViolation("Page must be 0..10000 and size 1..100");
    if (!products.existsById(id)) throw new NotFound();
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM product_change_audit WHERE product_id=?", Long.class, id);
    var rows =
        jdbc.query(
            """
            SELECT id,actor_id,reason,occurred_at,before_state,after_state
            FROM product_change_audit WHERE product_id=?
            ORDER BY product_version DESC LIMIT ? OFFSET ?
            """,
            (rs, n) ->
                new AuditEntry(
                    rs.getString("id"),
                    rs.getString("actor_id"),
                    rs.getString("reason"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    decode(rs.getString("before_state")),
                    decode(rs.getString("after_state"))),
            id,
            size,
            page * size);
    return new AuditPage(rows, page, size, total);
  }

  private static void admin(Actor actor) {
    if (actor == null || actor.role() != AppUser.Role.ADMIN)
      throw new AccessDeniedException("Administrator required");
  }

  private ProductView view(Product product) {
    return new ProductView(product.getId(), product.getSku(), snapshot(product));
  }

  private Snapshot snapshot(Product p) {
    return new Snapshot(
        p.getName(),
        p.getAmount(),
        p.getCurrency(),
        p.isActive(),
        p.getStock().onHand(),
        p.getStock().reserved(),
        p.getVersion());
  }

  private String encode(Snapshot snapshot) {
    try {
      return json.writeValueAsString(snapshot);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Cannot encode catalog audit", failure);
    }
  }

  private Snapshot decode(String snapshot) {
    try {
      return json.readValue(snapshot, Snapshot.class);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Cannot decode catalog audit", failure);
    }
  }
}
