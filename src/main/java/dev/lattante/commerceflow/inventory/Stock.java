package dev.lattante.commerceflow.inventory;

import dev.lattante.commerceflow.shared.DomainViolation;

/**
 * Immutable stock invariants; transactional database reservation comes with the orders increment.
 */
public record Stock(int onHand, int reserved) {
  public Stock {
    if (onHand < 0 || reserved < 0 || reserved > onHand)
      throw new DomainViolation("Invalid stock balance");
  }

  public int available() {
    return onHand - reserved;
  }

  public Stock reserve(int quantity) {
    positive(quantity);
    if (quantity > available()) throw new InsufficientStock();
    return new Stock(onHand, reserved + quantity);
  }

  public Stock release(int quantity) {
    positive(quantity);
    if (quantity > reserved) throw new DomainViolation("Cannot release unreserved stock");
    return new Stock(onHand, reserved - quantity);
  }

  public Stock commit(int quantity) {
    positive(quantity);
    if (quantity > reserved) throw new DomainViolation("Cannot consume unreserved stock");
    return new Stock(onHand - quantity, reserved - quantity);
  }

  private static void positive(int quantity) {
    if (quantity < 1) throw new DomainViolation("Quantity must be positive");
  }
}
