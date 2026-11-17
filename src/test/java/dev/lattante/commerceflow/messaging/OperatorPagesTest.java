package dev.lattante.commerceflow.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.lattante.commerceflow.identity.*;
import dev.lattante.commerceflow.shared.DomainViolation;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.*;
import org.springframework.security.access.AccessDeniedException;

class OperatorPagesTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final DispatchStore dispatch = mock(DispatchStore.class);
  private final MessageOperations operations =
      new MessageOperations(jdbc, dispatch, Clock.systemUTC());
  private final Actor admin = new Actor("admin", "admin@example.test", AppUser.Role.ADMIN);

  @Test
  void paginatedReadsEnforceServiceRoleBeforeSql() {
    for (Actor actor :
        Arrays.asList(
            null, new Actor("customer", "customer@example.test", AppUser.Role.CUSTOMER))) {
      assertThatThrownBy(() -> operations.deliveries(actor, null, null, 0, 20))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> operations.deadLetterPage(actor, null, 0, 20))
          .isInstanceOf(AccessDeniedException.class);
    }
    verifyNoInteractions(jdbc, dispatch);
  }

  @Test
  void invalidPagesCannotReachDatabase() {
    assertThatThrownBy(() -> operations.deliveries(admin, null, null, -1, 20))
        .isInstanceOf(DomainViolation.class);
    assertThatThrownBy(() -> operations.deliveries(admin, null, null, 0, 101))
        .isInstanceOf(DomainViolation.class);
    assertThatThrownBy(() -> operations.deadLetterPage(admin, null, 10001, 20))
        .isInstanceOf(DomainViolation.class);
    verifyNoInteractions(jdbc, dispatch);
  }

  @Test
  @SuppressWarnings("unchecked")
  void pagingSqlNeverSelectsMessagePayloadKeysOrDigests() {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    operations.deliveries(admin, MessageOperations.DeliveryState.FAILED, null, 0, 20);
    operations.deadLetterPage(admin, null, 0, 20);
    var sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, times(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
    assertThat(sql.getAllValues())
        .allSatisfy(
            query -> assertThat(query).doesNotContain("payload", "message_key", "payload_hash"));
  }
}
