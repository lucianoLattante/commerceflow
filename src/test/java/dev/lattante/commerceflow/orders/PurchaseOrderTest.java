package dev.lattante.commerceflow.orders;

import static org.junit.jupiter.api.Assertions.*;

import dev.lattante.commerceflow.inventory.InventoryReservation.ReservedItem;
import dev.lattante.commerceflow.shared.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PurchaseOrderTest {
  private ReservedItem item(String value, Money.Currency currency, int quantity) {
    return new ReservedItem(
        UUID.randomUUID(), "SKU", "Snapshot", new Money(new BigDecimal(value), currency), quantity);
  }

  @Test
  void totalsComeFromServerPricesAndQuantities() {
    var order =
        PurchaseOrder.reserved(
            "customer",
            List.of(item("1.23", Money.Currency.USD, 3), item("2.34", Money.Currency.USD, 2)),
            Instant.EPOCH);
    assertEquals(new BigDecimal("8.37"), order.total());
    assertEquals(OrderStatus.RESERVED, order.status());
    assertEquals(new BigDecimal("3.69"), order.itemViews().getFirst().lineTotal());
  }

  @Test
  void mixedCurrenciesAreRejected() {
    assertThrows(
        DomainViolation.class,
        () ->
            PurchaseOrder.reserved(
                "customer",
                List.of(item("1", Money.Currency.USD, 1), item("1", Money.Currency.ARS, 1)),
                Instant.EPOCH));
  }

  @Test
  void overflowingTotalsAreRejected() {
    assertThrows(
        DomainViolation.class,
        () ->
            PurchaseOrder.reserved(
                "customer",
                List.of(item("99999999999999999.99", Money.Currency.USD, 2)),
                Instant.EPOCH));
  }

  @Test
  void orderCopiesItsSnapshots() {
    var items = new ArrayList<ReservedItem>();
    items.add(item("1.20", Money.Currency.USD, 2));
    var order = PurchaseOrder.reserved("customer", items, Instant.EPOCH);
    items.clear();
    assertEquals(1, order.itemViews().size());
    assertEquals(new BigDecimal("2.40"), order.total());
    assertThrows(UnsupportedOperationException.class, () -> order.itemViews().clear());
  }
}
