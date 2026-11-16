package dev.lattante.commerceflow.shared;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class MoneyTest {
  private Money usd(String value) {
    return new Money(new BigDecimal(value), Money.Currency.USD);
  }

  @Test
  void addsDecimalValuesExactly() {
    assertEquals(new BigDecimal("0.30"), usd("0.10").add(usd("0.20")).amount());
  }

  @Test
  void normalizesTrailingZeroesWithoutRounding() {
    assertEquals(new BigDecimal("1.23"), usd("1.2300").amount());
  }

  @Test
  void rejectsFractionalCents() {
    assertThrows(DomainViolation.class, () -> usd("1.001"));
  }

  @Test
  void rejectsNegativeAmounts() {
    assertThrows(DomainViolation.class, () -> usd("-0.01"));
  }

  @Test
  void requiresAmountAndCurrency() {
    assertThrows(DomainViolation.class, () -> new Money(null, Money.Currency.USD));
    assertThrows(DomainViolation.class, () -> new Money(BigDecimal.ONE, null));
  }

  @Test
  void cannotAddDifferentCurrencies() {
    assertThrows(
        DomainViolation.class, () -> usd("1").add(new Money(BigDecimal.ONE, Money.Currency.ARS)));
  }

  @Test
  void multiplicationPreservesValueAndCurrency() {
    assertEquals(usd("37.02"), usd("12.34").multiply(3));
  }

  @Test
  void rejectsTotalOverflow() {
    assertThrows(DomainViolation.class, () -> usd("99999999999999999.99").add(usd("0.01")));
  }

  @Test
  void rejectsMultiplicationOverflow() {
    assertThrows(DomainViolation.class, () -> usd("99999999999999999.99").multiply(2));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void rejectsNonPositiveQuantities(int quantity) {
    assertThrows(DomainViolation.class, () -> usd("1").multiply(quantity));
  }

  @ParameterizedTest
  @EnumSource(Money.Currency.class)
  void explicitlySupportsSelectedCurrencies(Money.Currency currency) {
    assertEquals(currency, new Money(BigDecimal.ONE, currency).currency());
  }
}
