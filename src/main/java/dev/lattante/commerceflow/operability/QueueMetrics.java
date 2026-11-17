package dev.lattante.commerceflow.operability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class QueueMetrics implements MeterBinder {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public QueueMetrics(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (String state : List.of("PENDING", "IN_FLIGHT", "SENT", "FAILED"))
      Gauge.builder("commerceflow.dispatch.messages", this, ignored -> count(state))
          .tag("state", state.toLowerCase(java.util.Locale.ROOT))
          .register(registry);
    Gauge.builder("commerceflow.dispatch.oldest.unconfirmed.seconds", this, ignored -> oldest())
        .register(registry);
    Gauge.builder("commerceflow.dead.letters", this, ignored -> deadLetters()).register(registry);
  }

  private double count(String state) {
    try {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM message_dispatch WHERE state=?", Long.class, state);
    } catch (RuntimeException unavailable) {
      return Double.NaN;
    }
  }

  private double oldest() {
    try {
      Timestamp first =
          jdbc.queryForObject(
              "SELECT MIN(created_at) FROM message_dispatch WHERE state<>'SENT'", Timestamp.class);
      return first == null
          ? 0
          : Math.max(0, Duration.between(first.toInstant(), clock.instant()).toMillis() / 1000.0);
    } catch (RuntimeException unavailable) {
      return Double.NaN;
    }
  }

  private double deadLetters() {
    try {
      return jdbc.queryForObject("SELECT COUNT(*) FROM message_dead_letters", Long.class);
    } catch (RuntimeException unavailable) {
      return Double.NaN;
    }
  }
}
