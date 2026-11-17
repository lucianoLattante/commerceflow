package dev.lattante.commerceflow.messaging;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DispatchStore {
  private final JdbcTemplate jdbc;
  private final Clock clock;
  public DispatchStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
  public record Lease(String id, String kind, String sourceId, String topic,
      String key, String payload, int attempt, String token) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public String enqueue(String kind, String source, String aggregate, Integer sequence,
      String topic, String key, String payload, Instant time) {
    String id = UUID.randomUUID().toString();
    jdbc.update("""
        INSERT INTO message_dispatch(id,kind,source_id,aggregate_id,aggregate_sequence,
          topic,message_key,payload,state,next_attempt_at,created_at)
        VALUES(?,?,?,?,?,?,?,?,'PENDING',?,?)
        """, id, kind, source, aggregate, sequence, topic, key, payload,
        Timestamp.from(time), Timestamp.from(time));
    return id;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<Lease> claim() {
    Instant now = now();
    jdbc.update("""
        UPDATE message_dispatch SET state='FAILED',lease_token=NULL,lease_until=NULL,
          last_error_code='LEASE_EXHAUSTED'
        WHERE state='IN_FLIGHT' AND lease_until<=? AND attempts>=5
        """, Timestamp.from(now));
    var rows = jdbc.query("""
        SELECT d.* FROM message_dispatch d
        WHERE ((d.state='PENDING' AND d.next_attempt_at<=?)
          OR (d.state='IN_FLIGHT' AND d.lease_until<=?))
          AND d.attempts<5
          AND (d.kind<>'ORDER' OR NOT EXISTS (
            SELECT 1 FROM message_dispatch prior
            WHERE prior.aggregate_id=d.aggregate_id AND prior.aggregate_sequence<d.aggregate_sequence
              AND prior.state<>'SENT'))
        ORDER BY d.created_at,d.id LIMIT 1 FOR UPDATE SKIP LOCKED
        """, (rs, n) -> new Lease(rs.getString("id"), rs.getString("kind"), rs.getString("source_id"),
        rs.getString("topic"), rs.getString("message_key"), rs.getString("payload"),
        rs.getInt("attempts")+1, UUID.randomUUID().toString()),
        Timestamp.from(now), Timestamp.from(now));
    if (rows.isEmpty()) return Optional.empty();
    Lease row = rows.getFirst();
    jdbc.update("""
        UPDATE message_dispatch SET state='IN_FLIGHT',attempts=?,lease_token=?,lease_until=?
        WHERE id=?
        """, row.attempt(), row.token(), Timestamp.from(now.plusSeconds(30)), row.id());
    return Optional.of(row);
  }

  @Transactional
  public boolean complete(Lease lease) {
    Instant now = now();
    int changed = jdbc.update("""
        UPDATE message_dispatch SET state='SENT',sent_at=?,lease_token=NULL,lease_until=NULL,
          last_error_code=NULL WHERE id=? AND state='IN_FLIGHT' AND lease_token=?
        """, Timestamp.from(now), lease.id(), lease.token());
    if (changed == 1 && lease.kind().equals("ORDER")) {
      if (jdbc.update("UPDATE outbox_events SET published_at=? WHERE id=?",
          Timestamp.from(now), lease.sourceId()) != 1)
        throw new IllegalStateException("Missing source outbox event");
    }
    return changed == 1;
  }

  @Transactional
  public void failed(Lease lease) {
    jdbc.update("""
        UPDATE message_dispatch SET state=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,
          last_error_code='TRANSPORT_FAILURE' WHERE id=? AND state='IN_FLIGHT' AND lease_token=?
        """, lease.attempt() >= 5 ? "FAILED" : "PENDING",
        Timestamp.from(now().plusSeconds(backoffSeconds(lease.attempt()))), lease.id(), lease.token());
  }

  public static long backoffSeconds(int attempt) {
    if (attempt < 1 || attempt > 5) throw new IllegalArgumentException("Invalid attempt");
    return 1L << (attempt-1);
  }

  Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
