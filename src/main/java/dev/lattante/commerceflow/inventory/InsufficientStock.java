package dev.lattante.commerceflow.inventory;

import dev.lattante.commerceflow.shared.DomainViolation;

public class InsufficientStock extends DomainViolation {
  public InsufficientStock() {
    super("Insufficient available stock");
  }
}
