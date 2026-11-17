package dev.lattante.commerceflow.identity;

import dev.lattante.commerceflow.shared.DomainViolation;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@Service
public class ExpiredTokenCleanup {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final MeterRegistry metrics;

  public ExpiredTokenCleanup(JdbcTemplate jdbc, Clock clock, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.metrics = metrics;
  }

  /** Internal scheduler API. The caller cannot supply an expiry cutoff. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public int clean(int size) {
    if (size < 1 || size > 1000) throw new DomainViolation("Cleanup batch must be 1..1000");
    Timestamp cutoff = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
    // Row locks last until this transaction ends; concurrent workers skip another batch's rows.
    List<String> digests =
        jdbc.queryForList(
            """
            SELECT digest FROM access_tokens WHERE expires_at<=?
            ORDER BY expires_at,digest LIMIT ? FOR UPDATE SKIP LOCKED
            """,
            String.class,
            cutoff,
            size);
    int deleted = 0;
    // Unique-PK deletes avoid taking locks on rows claimed by another worker.
    for (String digest : digests) {
      deleted +=
          jdbc.update("DELETE FROM access_tokens WHERE digest=? AND expires_at<=?", digest, cutoff);
    }
    int committedDeleted = deleted;
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              metrics
                  .counter("commerceflow.identity.cleanup.runs", "outcome", "committed")
                  .increment();
              metrics.counter("commerceflow.identity.cleanup.deleted").increment(committedDeleted);
            } catch (RuntimeException unavailable) {
              org.slf4j.LoggerFactory.getLogger(ExpiredTokenCleanup.class)
                  .warn(
                      "Committed token cleanup metric unavailable: {}",
                      unavailable.getClass().getSimpleName());
            }
          }
        });
    return deleted;
  }
}
