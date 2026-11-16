package dev.lattante.commerceflow.shared;

public class DomainViolation extends RuntimeException {
  public DomainViolation(String message) {
    super(message);
  }
}
