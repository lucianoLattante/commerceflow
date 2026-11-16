package dev.lattante.commerceflow.orders;

public class IdempotencyConflict extends RuntimeException {
  public IdempotencyConflict() {
    super("Idempotency-Key was already used for a different order request");
  }
}
