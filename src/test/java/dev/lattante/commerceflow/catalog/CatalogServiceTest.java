package dev.lattante.commerceflow.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.lattante.commerceflow.shared.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

class CatalogServiceTest {
  private final ProductRepository repository = mock(ProductRepository.class);
  private final CatalogService service =
      new CatalogService(
          repository, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void invalidMoneyCannotReachPersistence() {
    assertThrows(
        DomainViolation.class,
        () -> service.create("SKU", "Item", new BigDecimal("0.001"), Money.Currency.USD, 10));
    verifyNoInteractions(repository);
  }

  @Test
  void invalidStockCannotReachPersistence() {
    assertThrows(
        DomainViolation.class,
        () -> service.create("SKU", "Item", BigDecimal.ONE, Money.Currency.USD, -1));
    verifyNoInteractions(repository);
  }

  @Test
  void zeroPriceCannotReachPersistence() {
    assertThrows(
        DomainViolation.class,
        () -> service.create("SKU", "Item", BigDecimal.ZERO, Money.Currency.USD, 1));
    verifyNoInteractions(repository);
  }

  @Test
  void invalidPaginationCannotReachDatabase() {
    assertThrows(DomainViolation.class, () -> service.list("", 0, 101));
    verifyNoInteractions(repository);
  }
}
