package dev.lattante.commerceflow.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DeadLetters {
  private final JdbcTemplate jdbc;
  private final DispatchStore dispatch;
  private final ObjectMapper json;
  private final Clock clock;
  public DeadLetters(JdbcTemplate jdbc, DispatchStore dispatch, ObjectMapper json, Clock clock) {
    this.jdbc = jdbc; this.dispatch = dispatch; this.json = json; this.clock = clock;
  }

  @Transactional
  public String capture(String topic, int partition, long offset, String key, String raw) {
    String id = MessageDigests.hash(topic + ":" + partition + ":" + offset);
    // Bounded poison retention. Tombstones and oversized values remain inspectable but unreplayable.
    boolean replayable = raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= OrderMessageCodec.MAX_BYTES
        && key != null && key.length() <= 128;
    String retained = replayable ? raw : null;
    String retainedKey = key != null && key.length() <= 128 ? key : null;
    int added = jdbc.update("""
        INSERT INTO message_dead_letters(id,source_topic,source_partition,source_offset,
          message_key,payload,payload_hash,replayable,reason_code,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=message_dead_letters.id
        """, id, topic, partition, offset, retainedKey, retained, MessageDigests.hash(raw == null ? "<tombstone>" : raw),
        replayable, replayable ? "CONSUMER_EXHAUSTED" : "UNREPLAYABLE_RECORD", Timestamp.from(clock.instant()));
    // JDBC affected-row settings differ for no-op upserts; use a unique dispatch source check under the DLQ row lock.
    jdbc.queryForMap("SELECT id FROM message_dead_letters WHERE id=? FOR UPDATE", id);
    if (jdbc.queryForObject("SELECT COUNT(*) FROM message_dispatch WHERE kind='DLQ' AND source_id=?",
        Long.class, id) == 0) {
      try {
        var envelope = new LinkedHashMap<String,Object>();
        envelope.put("deadLetterId", id); envelope.put("sourceTopic", topic);
        envelope.put("sourcePartition", partition); envelope.put("sourceOffset", offset);
        envelope.put("payloadHash", MessageDigests.hash(raw == null ? "<tombstone>" : raw));
        envelope.put("replayable", replayable); envelope.put("originalKey", retainedKey);
        envelope.put("originalPayload", retained);
        dispatch.enqueue("DLQ", id, null, null, OrderMessageCodec.DLQ, id,
            json.writeValueAsString(envelope), clock.instant());
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalStateException("Cannot serialize dead-letter envelope", e);
      }
    }
    return id;
  }
}
