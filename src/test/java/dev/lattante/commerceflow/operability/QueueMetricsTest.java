package dev.lattante.commerceflow.operability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QueueMetricsTest {
  @Test
  void observationFailureReportsUnknownRatherThanFalseZero() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"));
    var registry = new SimpleMeterRegistry();
    new QueueMetrics(jdbc, Clock.systemUTC()).bindTo(registry);
    assertThat(
            registry.get("commerceflow.dispatch.messages").tag("state", "pending").gauge().value())
        .isNaN();
  }

  @Test
  void unconfirmedAgeUsesTheApplicationClockAndNeverBecomesNegative() {
    var jdbc = mock(JdbcTemplate.class);
    Instant now = Instant.parse("2026-01-01T00:00:10Z");
    when(jdbc.queryForObject(anyString(), eq(Timestamp.class)))
        .thenReturn(Timestamp.from(now.minusSeconds(7)));
    var registry = new SimpleMeterRegistry();
    new QueueMetrics(jdbc, Clock.fixed(now, ZoneOffset.UTC)).bindTo(registry);
    assertThat(registry.get("commerceflow.dispatch.oldest.unconfirmed.seconds").gauge().value())
        .isEqualTo(7);
    when(jdbc.queryForObject(anyString(), eq(Timestamp.class)))
        .thenReturn(Timestamp.from(now.plusSeconds(2)));
    assertThat(registry.get("commerceflow.dispatch.oldest.unconfirmed.seconds").gauge().value())
        .isZero();
  }
}
