package dev.lattante.commerceflow.inventory;

import dev.lattante.commerceflow.shared.Money;
import java.util.List;
import java.util.UUID;

/** Must be invoked inside the transaction that records the order and outbox event. */
public interface InventoryReservation {
  record Demand(UUID productId, int quantity) {}

  record ReservedItem(UUID productId, String sku, String name, Money unitPrice, int quantity) {}

  List<ReservedItem> reserve(List<Demand> demands);
}
