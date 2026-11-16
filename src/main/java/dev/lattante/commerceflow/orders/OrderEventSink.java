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

  void orderReserved(ReservedOrder event);
}
