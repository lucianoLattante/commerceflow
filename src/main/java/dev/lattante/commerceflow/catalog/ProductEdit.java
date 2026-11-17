package dev.lattante.commerceflow.catalog;

import dev.lattante.commerceflow.shared.*;
import java.math.BigDecimal;

/** Complete mutable catalog state; SKU, reservations and versions are never caller-assigned. */
public record ProductEdit(
    long expectedVersion,
    String name,
    BigDecimal amount,
    Money.Currency currency,
    int onHand,
    boolean active,
    String reason) {
  public ProductEdit {
    if (expectedVersion < 0
        || name == null
        || name.isBlank()
        || name.length() > 160
        || onHand < 0
        || onHand > 1000000
        || reason == null
        || reason.isBlank()
        || reason.length() > 160) throw new DomainViolation("Invalid product change");
    name = name.strip();
    reason = reason.strip();
    Money price = new Money(amount, currency);
    if (price.amount().signum() == 0) throw new DomainViolation("Product price must be positive");
    amount = price.amount();
  }
}
