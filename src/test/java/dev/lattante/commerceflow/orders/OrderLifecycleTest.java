package dev.lattante.commerceflow.orders;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.lattante.commerceflow.inventory.InventoryReservation;
import dev.lattante.commerceflow.inventory.InventoryReservation.ReservedItem;
import dev.lattante.commerceflow.shared.Money;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Pageable;

class OrderLifecycleTest {
  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00.123456Z");

  private PurchaseOrder order() {
    return PurchaseOrder.reserved("owner",
        List.of(new ReservedItem(UUID.randomUUID(), "SKU", "Item",
            new Money(new BigDecimal("12.34"), Money.Currency.USD), 2)), CREATED);
  }

  @Test
  void deadlineIsInclusiveAndDerivedFromCreation() {
    var o = order();
    assertThat(o.expiresAt()).isEqualTo(CREATED.plusSeconds(900));
    assertThat(o.due(o.expiresAt().minusNanos(1))).isFalse();
    assertThat(o.due(o.expiresAt())).isTrue();
    assertThat(o.due(o.expiresAt().plusSeconds(1))).isTrue();
  }

  @ParameterizedTest
  @EnumSource(PaymentDecision.class)
  void paymentRecordsAnImmutableOutcomeAndRejectsChangedRetries(PaymentDecision decision) {
    var o = order();
    String key = OrderSubmission.keyHash("payment-key-01");
    o.pay(key, decision, CREATED.plusSeconds(10));
    assertThat(o.status()).isEqualTo(decision == PaymentDecision.APPROVED
        ? OrderStatus.PAID : OrderStatus.PAYMENT_DECLINED);
    assertThat(o.total()).isEqualByComparingTo("24.68");
    assertThat(o.completedAt()).isEqualTo(CREATED.plusSeconds(10));
    assertThat(o.paymentReplay(key, decision)).isTrue();
    assertThatThrownBy(() -> o.paymentReplay(OrderSubmission.keyHash("different-key"), decision))
        .isInstanceOf(IdempotencyConflict.class);
    PaymentDecision other = decision == PaymentDecision.APPROVED
        ? PaymentDecision.DECLINED : PaymentDecision.APPROVED;
    assertThatThrownBy(() -> o.paymentReplay(key, other)).isInstanceOf(IdempotencyConflict.class);
  }

  @ParameterizedTest
  @EnumSource(value = OrderStatus.class, names = {"PAID", "PAYMENT_DECLINED", "CANCELLED", "EXPIRED"})
  void terminalOrdersCannotTransitionAgain(OrderStatus status) {
    var o = order();
    switch (status) {
      case PAID -> o.pay("digest", PaymentDecision.APPROVED, CREATED);
      case PAYMENT_DECLINED -> o.pay("digest", PaymentDecision.DECLINED, CREATED);
      case CANCELLED -> o.cancel(CREATED);
      case EXPIRED -> o.expire(o.expiresAt());
      default -> throw new AssertionError();
    }
    var completed = o.completedAt();
    assertThatThrownBy(() -> o.pay("other", PaymentDecision.APPROVED, CREATED.plusSeconds(901)))
        .isInstanceOf(OrderStateConflict.class);
    assertThatThrownBy(() -> o.cancel(CREATED.plusSeconds(901))).isInstanceOf(OrderStateConflict.class);
    assertThatThrownBy(() -> o.expire(CREATED.plusSeconds(901))).isInstanceOf(OrderStateConflict.class);
    assertThat(o.status()).isEqualTo(status);
    assertThat(o.completedAt()).isEqualTo(completed);
  }

  @Test
  void anElapsedLeaseCannotBePaidOrCancelledAndAnEarlyLeaseCannotExpire() {
    var o = order();
    assertThatThrownBy(() -> o.expire(o.expiresAt().minusNanos(1)))
        .isInstanceOf(OrderStateConflict.class);
    assertThatThrownBy(() -> o.pay("digest", PaymentDecision.APPROVED, o.expiresAt()))
        .isInstanceOf(OrderStateConflict.class);
    assertThatThrownBy(() -> o.cancel(o.expiresAt())).isInstanceOf(OrderStateConflict.class);
    assertThat(o.status()).isEqualTo(OrderStatus.RESERVED);
    assertThat(o.completedAt()).isNull();
    o.expire(o.expiresAt());
    assertThat(o.status()).isEqualTo(OrderStatus.EXPIRED);
  }

  @Test
  void clockRegressionCannotCompleteAnOrder() {
    var o = order();
    assertThatThrownBy(() -> o.cancel(CREATED.minusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(o.status()).isEqualTo(OrderStatus.RESERVED);
  }

  @Test
  void expiryWorkerBoundsItsBatchAndContinuesAfterOneRolledBackOrder() {
    OrderLifecycleService service = mock(OrderLifecycleService.class);
    when(service.dueCandidates())
        .thenReturn(List.of("first", "failing", "last"));
    when(service.expire("failing")).thenThrow(new IllegalStateException("synthetic"));
    var worker = new ReservationExpiryWorker(service);
    worker.expireDue();
    verify(service).expire("first");
    verify(service).expire("failing");
    verify(service).expire("last");
  }

  @Test
  void expiryCandidateQueryIsBounded() {
    OrderRepository repository = mock(OrderRepository.class);
    var service = new OrderLifecycleService(repository, mock(InventoryReservation.class),
        mock(OrderEventSink.class), Clock.fixed(CREATED, ZoneOffset.UTC));
    service.dueCandidates();
    var page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
    verify(repository).dueIds(eq(CREATED), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(100);
    assertThat(page.getValue().getPageNumber()).isZero();
  }
}
