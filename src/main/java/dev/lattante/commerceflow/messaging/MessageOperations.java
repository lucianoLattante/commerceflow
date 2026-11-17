package dev.lattante.commerceflow.messaging;

import dev.lattante.commerceflow.identity.Actor;
import dev.lattante.commerceflow.shared.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class MessageOperations {
  private final JdbcTemplate jdbc;
  private final DispatchStore dispatch;
  private final Clock clock;

  public MessageOperations(JdbcTemplate jdbc, DispatchStore dispatch, Clock clock) {
    this.jdbc = jdbc;
    this.dispatch = dispatch;
    this.clock = clock;
  }

  public record Delivery(
      String id, String kind, String state, int attempts, int retryCycles, String errorCode) {}

  public record DeadLetter(
      String id,
      String sourceTopic,
      int sourcePartition,
      long sourceOffset,
      boolean replayable,
      int replayCount) {}

  @Transactional(readOnly = true)
  public List<Delivery> failures(int size) {
    limit(size);
    return jdbc.query(
        "SELECT id,kind,state,attempts,retry_cycles,last_error_code FROM message_dispatch WHERE"
            + " state='FAILED' ORDER BY created_at,id LIMIT ?",
        (rs, n) ->
            new Delivery(
                rs.getString("id"),
                rs.getString("kind"),
                rs.getString("state"),
                rs.getInt("attempts"),
                rs.getInt("retry_cycles"),
                rs.getString("last_error_code")),
        size);
  }

  @Transactional(readOnly = true)
  public List<DeadLetter> deadLetters(int size) {
    limit(size);
    return jdbc.query(
        "SELECT id,source_topic,source_partition,source_offset,replayable,replay_count FROM"
            + " message_dead_letters ORDER BY created_at DESC,id LIMIT ?",
        (rs, n) ->
            new DeadLetter(
                rs.getString("id"),
                rs.getString("source_topic"),
                rs.getInt("source_partition"),
                rs.getLong("source_offset"),
                rs.getBoolean("replayable"),
                rs.getInt("replay_count")),
        size);
  }

  private void limit(int size) {
    if (size < 1 || size > 100) throw new DomainViolation("Size must be 1..100");
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Delivery replay(String id, Actor actor, String key) {
    admin(actor);
    String digest = MessageDigests.key(key);
    var rows = jdbc.queryForList("SELECT * FROM message_dead_letters WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new NotFound();
    var prior = previous(id, "REPLAY", digest);
    if (prior != null) return delivery(prior);
    var row = rows.getFirst();
    if (!Boolean.TRUE.equals(row.get("replayable"))
        || ((Number) row.get("replay_count")).intValue() >= 3) throw new MessageFailure.Conflict();
    String delivery =
        dispatch.enqueue(
            "REPLAY",
            id,
            null,
            null,
            OrderMessageCodec.TOPIC,
            (String) row.get("message_key"),
            (String) row.get("payload"),
            clock.instant());
    jdbc.update("UPDATE message_dead_letters SET replay_count=replay_count+1 WHERE id=?", id);
    audit(id, "REPLAY", digest, delivery, actor);
    return delivery(delivery);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Delivery retry(String id, Actor actor, String key) {
    admin(actor);
    String digest = MessageDigests.key(key);
    var rows = jdbc.queryForList("SELECT * FROM message_dispatch WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new NotFound();
    var prior = previous(id, "RETRY", digest);
    if (prior != null) return delivery(prior);
    var row = rows.getFirst();
    if (!"FAILED".equals(row.get("state")) || ((Number) row.get("retry_cycles")).intValue() >= 3)
      throw new MessageFailure.Conflict();
    jdbc.update(
        """
        UPDATE message_dispatch SET state='PENDING',attempts=0,retry_cycles=retry_cycles+1,
          last_error_code=NULL,next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=?
        """,
        Timestamp.from(clock.instant()),
        id);
    audit(id, "RETRY", digest, id, actor);
    return delivery(id);
  }

  private void admin(Actor actor) {
    if (actor.role() != dev.lattante.commerceflow.identity.AppUser.Role.ADMIN)
      throw new org.springframework.security.access.AccessDeniedException("Administrator required");
  }

  private String previous(String id, String action, String key) {
    var rows =
        jdbc.queryForList(
            "SELECT dispatch_id FROM message_operator_requests WHERE target_id=? AND action=? AND"
                + " key_hash=?",
            String.class,
            id,
            action,
            key);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void audit(String id, String action, String key, String delivery, Actor actor) {
    jdbc.update(
        """
        INSERT INTO message_operator_requests(id,target_id,action,key_hash,dispatch_id,actor_id,created_at)
        VALUES(?,?,?,?,?,?,?)
        """,
        UUID.randomUUID().toString(),
        id,
        action,
        key,
        delivery,
        actor.id(),
        Timestamp.from(clock.instant()));
  }

  private Delivery delivery(String id) {
    return jdbc.queryForObject(
        "SELECT id,kind,state,attempts,retry_cycles,last_error_code FROM message_dispatch WHERE"
            + " id=?",
        (rs, n) ->
            new Delivery(
                id,
                rs.getString("kind"),
                rs.getString("state"),
                rs.getInt("attempts"),
                rs.getInt("retry_cycles"),
                rs.getString("last_error_code")),
        id);
  }
}
