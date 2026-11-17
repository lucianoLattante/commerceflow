package dev.lattante.commerceflow.orders;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.lattante.commerceflow.identity.*;
import dev.lattante.commerceflow.inventory.InventoryReservation;
import dev.lattante.commerceflow.shared.Money;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderServiceTest {
  @Test
  void creationResponseUsesPersistedMicrosecondPrecision() {
    OrderRepository repository = mock(OrderRepository.class);
    InventoryReservation inventory = mock(InventoryReservation.class);
    IdempotencyClaims claims = mock(IdempotencyClaims.class);
    OrderEventSink events = mock(OrderEventSink.class);
    UUID product = UUID.randomUUID();
    Actor actor =
        new Actor(UUID.randomUUID().toString(), "fixture@example.test", AppUser.Role.CUSTOMER);
    Instant precise = Instant.parse("2026-01-01T00:00:00.123456789Z");
    when(claims.claim(eq(actor.id()), anyString(), anyString(), any()))
        .thenReturn(new IdempotencyClaims.Claim("request", true, null));
    when(inventory.reserve(anyList()))
        .thenReturn(
            List.of(
                new InventoryReservation.ReservedItem(
                    product, "SKU", "Item", new Money(BigDecimal.ONE, Money.Currency.USD), 1)));
    when(repository.saveAndFlush(any(PurchaseOrder.class))).thenAnswer(call -> call.getArgument(0));
    OrderService service =
        new OrderService(
            repository,
            inventory,
            claims,
            events,
            Clock.fixed(precise, ZoneOffset.UTC),
            mock(dev.lattante.commerceflow.operability.OrderOperationAudit.class));
    var created =
        service.create(
            actor,
            "timestamp-key",
            new OrderSubmission(List.of(new InventoryReservation.Demand(product, 1))));
    assertThat(created.order().createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
    ArgumentCaptor<OrderEventSink.ReservedOrder> event =
        ArgumentCaptor.forClass(OrderEventSink.ReservedOrder.class);
    verify(events).orderReserved(event.capture());
    assertThat(event.getValue().occurredAt()).isEqualTo(created.order().createdAt());
  }
}
