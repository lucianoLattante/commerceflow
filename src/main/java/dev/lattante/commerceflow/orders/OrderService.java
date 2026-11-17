package dev.lattante.commerceflow.orders;

import dev.lattante.commerceflow.identity.*;
import dev.lattante.commerceflow.inventory.InventoryReservation;
import dev.lattante.commerceflow.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OrderService {
  private final OrderRepository orders;
  private final InventoryReservation inventory;
  private final IdempotencyClaims claims;
  private final OrderEventSink events;
  private final Clock clock;

  public OrderService(
      OrderRepository orders,
      InventoryReservation inventory,
      IdempotencyClaims claims,
      OrderEventSink events,
      Clock clock) {
    this.orders = orders;
    this.inventory = inventory;
    this.claims = claims;
    this.events = events;
    this.clock = clock;
  }

  public record ItemView(
      String productId,
      String sku,
      String name,
      BigDecimal unitAmount,
      int quantity,
      BigDecimal lineTotal) {}

  public record OrderView(
      String id,
      String customerId,
      OrderStatus status,
      BigDecimal total,
      Money.Currency currency,
      List<ItemView> items,
      Instant createdAt, Instant expiresAt, Instant completedAt) {}

  public record Creation(OrderView order, boolean replayed) {}

  public record Summary(
      String id,
      OrderStatus status,
      BigDecimal total,
      Money.Currency currency,
      Instant createdAt, Instant expiresAt, Instant completedAt) {}

  public record OrderPage(List<Summary> content, int page, int size, long totalElements) {}

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Creation create(Actor actor, String key, OrderSubmission request) {
    Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var claim = claims.claim(actor.id(), key, request.fingerprint(), now);
    if (!claim.fresh()) return new Creation(view(owned(claim.orderId(), actor)), true);
    var reserved = inventory.reserve(request.items());
    PurchaseOrder order = orders.saveAndFlush(PurchaseOrder.reserved(actor.id(), reserved, now));
    claims.complete(claim.requestId(), order.id());
    events.orderReserved(
        new OrderEventSink.ReservedOrder(
            UUID.randomUUID(),
            order.id(),
            actor.id(),
            order.total(),
            order.currency(),
            reserved,
            now));
    return new Creation(view(order), false);
  }

  @Transactional(readOnly = true)
  public OrderView find(String id, Actor actor) {
    return view(owned(id, actor));
  }

  @Transactional(readOnly = true)
  public OrderPage list(Actor actor, OrderStatus status, int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new DomainViolation("Page must be 0..10000 and size 1..100");
    var pageable =
        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    var result =
        status == null
            ? orders.findByCustomerId(actor.id(), pageable)
            : orders.findByCustomerIdAndStatus(actor.id(), status, pageable);
    return new OrderPage(
        result.getContent().stream()
            .map(o -> new Summary(o.id(), o.status(), o.total(), o.currency(), o.createdAt(), o.expiresAt(), o.completedAt()))
            .toList(),
        page,
        size,
        result.getTotalElements());
  }

  private PurchaseOrder owned(String id, Actor actor) {
    PurchaseOrder order = orders.findById(id).orElseThrow(NotFound::new);
    if (!order.customerId().equals(actor.id()) && actor.role() != AppUser.Role.ADMIN)
      throw new NotFound();
    return order;
  }

  static OrderView view(PurchaseOrder order) {
    return new OrderView(
        order.id(),
        order.customerId(),
        order.status(),
        order.total(),
        order.currency(),
        order.itemViews(),
        order.createdAt(), order.expiresAt(), order.completedAt());
  }
}
