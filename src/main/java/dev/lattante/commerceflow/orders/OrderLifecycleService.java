package dev.lattante.commerceflow.orders;

import dev.lattante.commerceflow.identity.Actor;
import dev.lattante.commerceflow.inventory.InventoryReservation;
import dev.lattante.commerceflow.shared.NotFound;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OrderLifecycleService {
  private final OrderRepository orders;
  private final InventoryReservation inventory;
  private final OrderEventSink events;
  private final Clock clock;

  public OrderLifecycleService(OrderRepository orders, InventoryReservation inventory,
      OrderEventSink events, Clock clock) {
    this.orders = orders;
    this.inventory = inventory;
    this.events = events;
    this.clock = clock;
  }

  public record PaymentResult(OrderService.OrderView order, boolean replayed) {}

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PaymentResult pay(String id, Actor actor, String key, PaymentDecision decision) {
    String digest = OrderSubmission.keyHash(key);
    Objects.requireNonNull(decision);
    PurchaseOrder order = ownedLocked(id, actor);
    if (order.paymentReplay(digest, decision))
      return new PaymentResult(OrderService.view(order), true);
    // Read time after obtaining the lock: waiting for another request cannot extend the lease.
    Instant now = now();
    if (expireIfDue(order, now) || order.status() == OrderStatus.EXPIRED)
      return new PaymentResult(OrderService.view(order), false);
    order.pay(digest, decision, now);
    if (decision == PaymentDecision.APPROVED) inventory.consume(order.demands());
    else inventory.release(order.demands());
    record(order, now);
    return new PaymentResult(OrderService.view(order), false);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public OrderService.OrderView cancel(String id, Actor actor) {
    PurchaseOrder order = ownedLocked(id, actor);
    Instant now = now();
    if (expireIfDue(order, now)
        || order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.EXPIRED)
      return OrderService.view(order);
    order.cancel(now);
    inventory.release(order.demands());
    record(order, now);
    return OrderService.view(order);
  }

  /** Internal bounded candidate query; callers must recheck every ID under the order lock. */
  @Transactional(readOnly = true)
  public List<String> dueCandidates() {
    return orders.dueIds(now(), org.springframework.data.domain.PageRequest.of(0, 100));
  }

  /** Called once per transaction by a separate worker; never exposed as a public HTTP endpoint. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean expire(String id) {
    return orders.lockById(id).map(order -> expireIfDue(order, now())).orElse(false);
  }

  private boolean expireIfDue(PurchaseOrder order, Instant now) {
    if (!order.due(now)) return false;
    order.expire(now);
    inventory.release(order.demands());
    record(order, now);
    return true;
  }

  private PurchaseOrder ownedLocked(String id, Actor actor) {
    PurchaseOrder order = orders.lockById(id).orElseThrow(NotFound::new);
    // Administrators may read orders but cannot simulate payments/cancellations for other owners.
    if (!order.customerId().equals(actor.id())) throw new NotFound();
    return order;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private void record(PurchaseOrder order, Instant now) {
    // Flush JPA before the JDBC outbox write, but commit both with the same transaction manager.
    orders.flush();
    events.orderCompleted(new OrderEventSink.CompletedOrder(
        UUID.randomUUID(), order.id(), order.customerId(), order.status(),
        order.total(), order.currency(), now));
  }
}
