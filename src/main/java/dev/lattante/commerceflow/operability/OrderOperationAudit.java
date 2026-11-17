package dev.lattante.commerceflow.operability;

import dev.lattante.commerceflow.shared.DomainViolation;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@Service
public class OrderOperationAudit {
  public enum Action {
    ORDER_RESERVED,
    ORDER_PAID,
    ORDER_PAYMENT_DECLINED,
    ORDER_CANCELLED,
    ORDER_EXPIRED
  }

  public record Entry(
      String id, String orderId, String actorId, Action action, int sequence, Instant occurredAt) {}

  public record AuditPage(List<Entry> content, int page, int size, long totalElements) {}

  private final JdbcTemplate jdbc;
  private final MeterRegistry metrics;

  public OrderOperationAudit(JdbcTemplate jdbc, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.metrics = metrics;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(String order, String actor, Action action, Instant time) {
    Objects.requireNonNull(action);
    jdbc.update(
        """
        INSERT INTO order_operation_audit(id,order_id,actor_id,action,sequence_no,occurred_at)
        VALUES(?,?,?,?,?,?)
        """,
        UUID.randomUUID().toString(),
        order,
        actor,
        action.name(),
        action == Action.ORDER_RESERVED ? 1 : 2,
        Timestamp.from(time));
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              metrics
                  .counter(
                      "commerceflow.order.transitions",
                      "action",
                      action.name().toLowerCase(Locale.ROOT))
                  .increment();
            } catch (RuntimeException observationFailure) {
              org.slf4j.LoggerFactory.getLogger(OrderOperationAudit.class)
                  .warn(
                      "Committed order metric unavailable: {}",
                      observationFailure.getClass().getSimpleName());
            }
          }
        });
  }

  @Transactional(readOnly = true)
  public List<Entry> forOrder(String id) {
    return jdbc.query(
        "SELECT id,order_id,actor_id,action,sequence_no,occurred_at FROM order_operation_audit"
            + " WHERE order_id=? ORDER BY sequence_no",
        (rs, n) ->
            new Entry(
                rs.getString("id"),
                rs.getString("order_id"),
                rs.getString("actor_id"),
                Action.valueOf(rs.getString("action")),
                rs.getInt("sequence_no"),
                rs.getTimestamp("occurred_at").toInstant()),
        id);
  }

  @Transactional(readOnly = true)
  public AuditPage list(Action action, int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new DomainViolation("Page must be 0..10000 and size 1..100");
    String where = action == null ? "" : " WHERE action=?";
    List<Object> parameters = new ArrayList<>();
    if (action != null) parameters.add(action.name());
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM order_operation_audit" + where, Long.class, parameters.toArray());
    parameters.add(size);
    parameters.add(page * size);
    var rows =
        jdbc.query(
            "SELECT id,order_id,actor_id,action,sequence_no,occurred_at FROM order_operation_audit"
                + where
                + " ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs, n) ->
                new Entry(
                    rs.getString("id"),
                    rs.getString("order_id"),
                    rs.getString("actor_id"),
                    Action.valueOf(rs.getString("action")),
                    rs.getInt("sequence_no"),
                    rs.getTimestamp("occurred_at").toInstant()),
            parameters.toArray());
    return new AuditPage(rows, page, size, total);
  }
}
