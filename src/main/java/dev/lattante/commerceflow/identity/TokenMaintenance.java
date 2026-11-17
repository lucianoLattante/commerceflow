package dev.lattante.commerceflow.identity;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenMaintenance {
  public record Snapshot(
      boolean cleanupEnabled,
      int batchSize,
      long intervalMs,
      long expiredTokens,
      Instant oldestExpiredAt,
      Instant observedAt) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final TokenCleanupSettings settings;

  public TokenMaintenance(JdbcTemplate jdbc, Clock clock, TokenCleanupSettings settings) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.settings = settings;
  }

  @Transactional(readOnly = true)
  public Snapshot snapshot(Actor actor) {
    if (actor == null || actor.role() != AppUser.Role.ADMIN)
      throw new AccessDeniedException("Administrator required");
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    return jdbc.queryForObject(
        "SELECT COUNT(*),MIN(expires_at) FROM access_tokens WHERE expires_at<=?",
        (rs, n) -> {
          Timestamp oldest = rs.getTimestamp(2);
          return new Snapshot(
              settings.enabled(),
              settings.batchSize(),
              settings.intervalMs(),
              rs.getLong(1),
              oldest == null ? null : oldest.toInstant(),
              now);
        },
        Timestamp.from(now));
  }
}
