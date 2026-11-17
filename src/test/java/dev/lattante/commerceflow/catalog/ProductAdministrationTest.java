package dev.lattante.commerceflow.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lattante.commerceflow.identity.*;
import dev.lattante.commerceflow.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

class ProductAdministrationTest {
  private final ProductRepository repository = mock(ProductRepository.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final Actor admin =
      new Actor(UUID.randomUUID().toString(), "admin@example.test", AppUser.Role.ADMIN);
  private final ProductAdministration service =
      new ProductAdministration(
          repository,
          jdbc,
          new ObjectMapper(),
          Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

  private Product product() {
    return new Product(
        "SKU",
        "Original",
        new Money(new BigDecimal("12.34"), Money.Currency.USD),
        10,
        Instant.EPOCH);
  }

  private ProductEdit edit(long version, int onHand, boolean active) {
    return new ProductEdit(
        version,
        "Revised",
        new BigDecimal("20.00"),
        Money.Currency.USD,
        onHand,
        active,
        "Stock reconciliation");
  }

  @Test
  void serviceRejectsNonAdminBeforeAccessingPersistence() {
    var customer = new Actor("customer", "customer@example.test", AppUser.Role.CUSTOMER);
    for (Actor actor : Arrays.asList(null, customer)) {
      assertThrows(AccessDeniedException.class, () -> service.find("missing", actor));
      assertThrows(AccessDeniedException.class, () -> service.list(actor, null, 0, 20));
      assertThrows(
          AccessDeniedException.class, () -> service.update("missing", actor, edit(0, 10, true)));
      assertThrows(AccessDeniedException.class, () -> service.audit("missing", actor, 0, 20));
    }
    verifyNoInteractions(repository, jdbc);
  }

  @Test
  void staleVersionCannotMutateProduct() {
    var p = product();
    assertThrows(ProductConflict.class, () -> p.revise(edit(1, 5, false)));
    assertEquals("Original", p.getName());
    assertEquals(10, p.getStock().onHand());
    assertTrue(p.isActive());
  }

  @Test
  void reservedStockCannotBeRemoved() {
    var p = product();
    p.reserve(8);
    assertThrows(ProductConflict.class, () -> p.revise(edit(0, 7, false)));
    assertEquals(10, p.getStock().onHand());
    assertEquals(8, p.getStock().reserved());
    assertTrue(p.isActive());
  }

  @Test
  void inactiveProductsRejectNewReservationsButCanCompleteExistingOnes() {
    var p = product();
    p.reserve(4);
    assertTrue(p.revise(edit(0, 10, false)));
    assertThrows(ProductConflict.class, () -> p.reserve(1));
    p.consume(2);
    p.release(2);
    assertEquals(8, p.getStock().onHand());
    assertEquals(0, p.getStock().reserved());
    assertEquals("SKU", p.getSku());
  }

  @Test
  void identicalStateIsNotAnAuditedMutation() {
    var p = product();
    var unchanged =
        new ProductEdit(
            0,
            "Original",
            new BigDecimal("12.340"),
            Money.Currency.USD,
            10,
            true,
            "Repeated review");
    assertFalse(p.revise(unchanged));
    when(repository.lockById(p.getId())).thenReturn(Optional.of(p));
    assertEquals(0, service.update(p.getId(), admin, unchanged).state().version());
    verify(repository, never()).flush();
    verifyNoInteractions(jdbc);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 1000001})
  void stockBoundsAreDomainValidated(int onHand) {
    assertThrows(DomainViolation.class, () -> edit(0, onHand, true));
  }

  @Test
  void changeRejectsMissingReasonAndFractionalCents() {
    assertThrows(
        DomainViolation.class,
        () -> new ProductEdit(0, "New", BigDecimal.ONE, Money.Currency.USD, 5, true, " "));
    assertThrows(
        DomainViolation.class,
        () ->
            new ProductEdit(
                0, "New", new BigDecimal("1.001"), Money.Currency.USD, 5, true, "Valid reason"));
    assertThrows(
        DomainViolation.class,
        () ->
            new ProductEdit(
                0, "New", BigDecimal.ZERO, Money.Currency.USD, 5, true, "Valid reason"));
  }

  @Test
  void auditPaginationRejectedBeforePersistence() {
    assertThrows(DomainViolation.class, () -> service.audit("missing", admin, 0, 101));
    assertThrows(DomainViolation.class, () -> service.audit("missing", admin, -1, 20));
    verifyNoInteractions(repository, jdbc);
  }
}
