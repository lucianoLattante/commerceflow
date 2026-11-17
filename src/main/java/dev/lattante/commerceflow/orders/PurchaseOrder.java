package dev.lattante.commerceflow.orders;

import dev.lattante.commerceflow.inventory.InventoryReservation.ReservedItem;
import dev.lattante.commerceflow.shared.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import dev.lattante.commerceflow.inventory.InventoryReservation.Demand;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "customer_orders")
class PurchaseOrder {
  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "customer_id", length = 36, nullable = false)
  private String customerId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(length = 24, nullable = false)
  private OrderStatus status;

  @Column(name = "total_amount", precision = 19, scale = 2, nullable = false)
  private BigDecimal total;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(length = 3, nullable = false)
  private Money.Currency currency;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "payment_key_hash", length = 64)
  private String paymentKeyHash;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "payment_decision", length = 16)
  private PaymentDecision paymentDecision;

  @ElementCollection
  @CollectionTable(name = "order_lines", joinColumns = @JoinColumn(name = "order_id"))
  @OrderColumn(name = "position")
  private List<Line> lines = new ArrayList<>();

  protected PurchaseOrder() {}

  static PurchaseOrder reserved(String customerId, List<ReservedItem> reserved, Instant now) {
    if (reserved.isEmpty()) throw new DomainViolation("An order needs items");
    Money total = new Money(BigDecimal.ZERO, reserved.getFirst().unitPrice().currency());
    PurchaseOrder order = new PurchaseOrder();
    order.id = UUID.randomUUID().toString();
    order.customerId = customerId;
    order.createdAt = now;
    order.expiresAt = now.plus(Duration.ofMinutes(15));
    order.status = OrderStatus.RESERVED;
    for (ReservedItem item : reserved) {
      Money lineTotal = item.unitPrice().multiply(item.quantity());
      total = total.add(lineTotal);
      order.lines.add(new Line(item, lineTotal.amount()));
    }
    order.total = total.amount();
    order.currency = total.currency();
    return order;
  }

  boolean due(Instant now) {
    return status == OrderStatus.RESERVED && !now.isBefore(expiresAt);
  }

  void pay(String keyHash, PaymentDecision decision, Instant now) {
    Objects.requireNonNull(keyHash);
    Objects.requireNonNull(decision);
    if (due(now)) throw new OrderStateConflict();
    finish(decision == PaymentDecision.APPROVED
        ? OrderStatus.PAID : OrderStatus.PAYMENT_DECLINED, now);
    paymentKeyHash = keyHash;
    paymentDecision = decision;
  }

  boolean paymentReplay(String keyHash, PaymentDecision decision) {
    if (paymentKeyHash == null) return false;
    if (!paymentKeyHash.equals(keyHash) || paymentDecision != decision)
      throw new IdempotencyConflict();
    return true;
  }

  void cancel(Instant now) {
    if (due(now)) throw new OrderStateConflict();
    finish(OrderStatus.CANCELLED, now);
  }

  void expire(Instant now) {
    if (!due(now)) throw new OrderStateConflict();
    finish(OrderStatus.EXPIRED, now);
  }

  private void finish(OrderStatus target, Instant now) {
    if (status != OrderStatus.RESERVED) throw new OrderStateConflict();
    if (now.isBefore(createdAt)) throw new IllegalStateException("Clock precedes order creation");
    status = target;
    completedAt = now;
  }

  Instant expiresAt() { return expiresAt; }

  Instant completedAt() { return completedAt; }

  List<Demand> demands() {
    return lines.stream()
        .map(l -> new Demand(UUID.fromString(l.productId), l.quantity)).toList();
  }

  @Embeddable
  static class Line {
    @Column(name = "product_id", length = 36, nullable = false)
    private String productId;

    @Column(length = 64, nullable = false)
    private String sku;

    @Column(length = 160, nullable = false)
    private String name;

    @Column(name = "unit_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal unitAmount;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", precision = 19, scale = 2, nullable = false)
    private BigDecimal lineTotal;

    protected Line() {}

    Line(ReservedItem item, BigDecimal lineTotal) {
      this.productId = item.productId().toString();
      this.sku = item.sku();
      this.name = item.name();
      this.unitAmount = item.unitPrice().amount();
      this.quantity = item.quantity();
      this.lineTotal = lineTotal;
    }

    OrderService.ItemView view() {
      return new OrderService.ItemView(productId, sku, name, unitAmount, quantity, lineTotal);
    }
  }

  String id() {
    return id;
  }

  String customerId() {
    return customerId;
  }

  OrderStatus status() {
    return status;
  }

  BigDecimal total() {
    return total;
  }

  Money.Currency currency() {
    return currency;
  }

  Instant createdAt() {
    return createdAt;
  }

  List<OrderService.ItemView> itemViews() {
    return lines.stream().map(Line::view).toList();
  }
}
