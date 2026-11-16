package dev.lattante.commerceflow.orders;

import static org.junit.jupiter.api.Assertions.*;

import dev.lattante.commerceflow.inventory.InventoryReservation.Demand;
import dev.lattante.commerceflow.shared.DomainViolation;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderSubmissionTest {
  private final UUID a = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private final UUID b = UUID.fromString("00000000-0000-4000-8000-000000000002");

  @Test
  void reorderedInputsHaveTheSameFingerprint() {
    var one = new OrderSubmission(List.of(new Demand(a, 2), new Demand(b, 1)));
    var two = new OrderSubmission(List.of(new Demand(b, 1), new Demand(a, 2)));
    assertEquals(one.fingerprint(), two.fingerprint());
    assertEquals(a, two.items().getFirst().productId());
  }

  @Test
  void changedQuantityHasADifferentFingerprint() {
    assertNotEquals(
        new OrderSubmission(List.of(new Demand(a, 1))).fingerprint(),
        new OrderSubmission(List.of(new Demand(a, 2))).fingerprint());
  }

  @Test
  void copiesMutableInput() {
    var input = new ArrayList<Demand>();
    input.add(new Demand(a, 1));
    var normalized = new OrderSubmission(input);
    input.clear();
    assertEquals(1, normalized.items().size());
    assertThrows(UnsupportedOperationException.class, () -> normalized.items().clear());
  }

  @Test
  void duplicateProductIsRejected() {
    assertThrows(
        DomainViolation.class,
        () -> new OrderSubmission(List.of(new Demand(a, 1), new Demand(a, 2))));
  }

  @Test
  void emptyAndNullOrdersAreRejected() {
    assertThrows(DomainViolation.class, () -> new OrderSubmission(List.of()));
    assertThrows(DomainViolation.class, () -> new OrderSubmission(null));
  }

  @Test
  void tooManyLinesAreRejected() {
    var lines = new ArrayList<Demand>();
    for (int i = 0; i < 51; i++) lines.add(new Demand(UUID.randomUUID(), 1));
    assertThrows(DomainViolation.class, () -> new OrderSubmission(lines));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1001, Integer.MAX_VALUE})
  void invalidQuantitiesAreRejected(int quantity) {
    assertThrows(
        DomainViolation.class, () -> new OrderSubmission(List.of(new Demand(a, quantity))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"short", "space in key", "áááááááá", ""})
  void invalidIdempotencyKeysAreRejected(String key) {
    assertThrows(DomainViolation.class, () -> OrderSubmission.keyHash(key));
  }

  @Test
  void oversizedKeyIsRejected() {
    assertThrows(DomainViolation.class, () -> OrderSubmission.keyHash("a".repeat(129)));
  }

  @Test
  void onlyKeyDigestIsPersistable() {
    String raw = "order-key-0001";
    assertEquals(64, OrderSubmission.keyHash(raw).length());
    assertNotEquals(raw, OrderSubmission.keyHash(raw));
  }
}
