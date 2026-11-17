package dev.lattante.commerceflow.catalog;

import dev.lattante.commerceflow.shared.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  private final ProductRepository products;
  private final Clock clock;

  public CatalogService(ProductRepository products, Clock clock) {
    this.products = products;
    this.clock = clock;
  }

  public record ProductView(
      String id,
      String sku,
      String name,
      BigDecimal amount,
      Money.Currency currency,
      int available) {}

  public record PageView<T>(List<T> content, int page, int size, long totalElements) {}

  @Transactional
  public ProductView create(
      String sku, String name, BigDecimal amount, Money.Currency currency, int quantity) {
    Money price = new Money(amount, currency);
    if (price.amount().signum() == 0) throw new DomainViolation("Product price must be positive");
    return view(
        products.saveAndFlush(new Product(sku, name.strip(), price, quantity, clock.instant())));
  }

  @Transactional(readOnly = true)
  public ProductView find(String id) {
    return view(products.findById(id).filter(Product::isActive).orElseThrow(NotFound::new));
  }

  @Transactional(readOnly = true)
  public PageView<ProductView> list(String query, int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new DomainViolation("Page must be 0..10000 and size 1..100");
    if (query.length() > 160) throw new DomainViolation("Search text is too long");
    String literal =
        query
            .strip()
            .toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    Specification<Product> spec =
        (root, q, cb) ->
            cb.and(
                cb.isTrue(root.get("active")),
                cb.like(cb.lower(root.get("name")), "%" + literal + "%", '\\'));
    Page<Product> result = products.findAll(spec, PageRequest.of(page, size, Sort.by("sku")));
    return new PageView<>(
        result.map(CatalogService::view).getContent(), page, size, result.getTotalElements());
  }

  private static ProductView view(Product p) {
    return new ProductView(
        p.getId(),
        p.getSku(),
        p.getName(),
        p.getAmount(),
        p.getCurrency(),
        p.getStock().available());
  }
}
