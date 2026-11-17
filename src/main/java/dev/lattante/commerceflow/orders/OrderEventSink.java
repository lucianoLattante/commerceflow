package dev.lattante.commerceflow.orders;

import dev.lattante.commerceflow.inventory.InventoryReservation.ReservedItem;
import dev.lattante.commerceflow.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public interface OrderEventSink {
  record ReservedOrder(
      UUID eventId,
      String orderId,
      String customerId,
      BigDecimal total,
      Money.Currency currency,
      List<ReservedItem> items,
      Instant occurredAt) {
    public ReservedOrder {
      items = List.copyOf(items);
    }
  }

  record CompletedOrder(
      UUID eventId, String orderId, String customerId, OrderStatus status,
      BigDecimal total, Money.Currency currency, Instant occurredAt) {
    public CompletedOrder {
      Objects.requireNonNull(status);
      if (status == OrderStatus.RESERVED)
        throw new IllegalArgumentException("A completion event needs a terminal status");
    }
    public String type() { return "ORDER_" + status.name(); }
  }

  void orderCompleted(CompletedOrder event);

  void orderReserved(ReservedOrder event);
}
