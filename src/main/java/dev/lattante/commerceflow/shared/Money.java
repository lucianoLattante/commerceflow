package dev.lattante.commerceflow.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {
  private static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

  public enum Currency {
    USD,
    ARS,
    EUR
  }

  public Money {
    if (amount == null || currency == null || amount.signum() < 0 || amount.compareTo(MAX) > 0)
      throw new DomainViolation("Invalid monetary amount or currency");
    try {
      amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException ex) {
      throw new DomainViolation("Fractional cents are not supported");
    }
  }

  public Money add(Money other) {
    Objects.requireNonNull(other);
    if (currency != other.currency) throw new DomainViolation("Currencies must match");
    return new Money(amount.add(other.amount), currency);
  }

  public Money multiply(int quantity) {
    if (quantity < 1) throw new DomainViolation("Quantity must be positive");
    return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
  }
}
