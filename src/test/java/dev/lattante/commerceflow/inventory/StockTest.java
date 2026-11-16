package dev.lattante.commerceflow.inventory;

import static org.junit.jupiter.api.Assertions.*;

import dev.lattante.commerceflow.shared.DomainViolation;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class StockTest {
  @Test
  void reservationChangesAvailabilityWithoutConsumingStock() {
    Stock initial = new Stock(10, 0), held = initial.reserve(4);
    assertEquals(new Stock(10, 4), held);
    assertEquals(6, held.available());
    assertEquals(10, initial.available());
  }

  @Test
  void releaseRestoresAvailability() {
    assertEquals(new Stock(10, 1), new Stock(10, 4).release(3));
  }

  @Test
  void commitConsumesOnlyReservedUnits() {
    assertEquals(new Stock(7, 1), new Stock(10, 4).commit(3));
  }

  @Test
  void cannotOversell() {
    Stock s = new Stock(10, 8);
    assertThrows(DomainViolation.class, () -> s.reserve(3));
    assertEquals(2, s.available());
  }

  @Test
  void cannotReleaseUnreservedUnits() {
    assertThrows(DomainViolation.class, () -> new Stock(10, 2).release(3));
  }

  @Test
  void cannotCommitUnreservedUnits() {
    assertThrows(DomainViolation.class, () -> new Stock(10, 2).commit(3));
  }

  @Test
  void cannotReleaseTheSameReservationTwice() {
    Stock s = new Stock(10, 2).release(2);
    assertThrows(DomainViolation.class, () -> s.release(2));
  }

  @ParameterizedTest
  @CsvSource({"-1,0", "1,-1", "1,2"})
  void rejectsInvalidBalances(int hand, int reserved) {
    assertThrows(DomainViolation.class, () -> new Stock(hand, reserved));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsNonPositiveOperations(int quantity) {
    Stock s = new Stock(10, 5);
    assertThrows(DomainViolation.class, () -> s.reserve(quantity));
    assertThrows(DomainViolation.class, () -> s.release(quantity));
    assertThrows(DomainViolation.class, () -> s.commit(quantity));
  }

  @Test
  void boundaryReservationDoesNotOverflow() {
    assertEquals(
        new Stock(Integer.MAX_VALUE, Integer.MAX_VALUE),
        new Stock(Integer.MAX_VALUE, Integer.MAX_VALUE - 1).reserve(1));
  }

  @Test
  void validOperationSequencesConserveStock() {
    Random random = new Random(43905);
    Stock stock = new Stock(10000, 0);
    int consumed = 0;
    for (int i = 0; i < 5000; i++) {
      int quantity = 1 + random.nextInt(10), operation = random.nextInt(3);
      if (operation == 0 && quantity <= stock.available()) stock = stock.reserve(quantity);
      else if (operation == 1 && quantity <= stock.reserved()) stock = stock.release(quantity);
      else if (operation == 2 && quantity <= stock.reserved()) {
        stock = stock.commit(quantity);
        consumed += quantity;
      }
      assertEquals(10000 - consumed, stock.onHand());
      assertEquals(stock.onHand(), stock.available() + stock.reserved());
      assertTrue(stock.available() >= 0);
    }
  }
}
