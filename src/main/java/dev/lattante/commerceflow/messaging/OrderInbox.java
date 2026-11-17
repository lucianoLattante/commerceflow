package dev.lattante.commerceflow.messaging;

import dev.lattante.commerceflow.shared.NotFound;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OrderInbox {
  private final JdbcTemplate jdbc;
  private final OrderMessageCodec codec;
  private final Clock clock;
  public OrderInbox(JdbcTemplate jdbc, OrderMessageCodec codec, Clock clock) {
    this.jdbc = jdbc; this.codec = codec; this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean apply(String key, String raw) {
    var event = codec.decode(key, raw);
    String digest = MessageDigests.hash(raw), processing = UUID.randomUUID().toString();
    Timestamp now = Timestamp.from(clock.instant());
    jdbc.update("""
        INSERT INTO message_inbox(event_id,payload_hash,processing_id,processed_at) VALUES(?,?,?,?)
        ON DUPLICATE KEY UPDATE event_id=message_inbox.event_id
        """, event.eventId().toString(), digest, processing, now);
    var claim = jdbc.queryForMap("SELECT * FROM message_inbox WHERE event_id=? FOR UPDATE",
        event.eventId().toString());
    if (!digest.equals(claim.get("payload_hash"))) throw new MessageFailure();
    if (!processing.equals(claim.get("processing_id"))) return false;

    jdbc.update("""
        INSERT INTO order_projections(order_id,customer_id,status,sequence_no,received_at)
        VALUES(?,?,'PENDING',0,?) ON DUPLICATE KEY UPDATE order_id=order_projections.order_id
        """, key, event.customerId(), now);
    var row = jdbc.queryForMap("SELECT * FROM order_projections WHERE order_id=? FOR UPDATE", key);
    int sequence = ((Number) row.get("sequence_no")).intValue();
    if (!event.customerId().equals(row.get("customer_id"))) throw new MessageFailure();
    if (event.sequence() > sequence+1) throw new MessageFailure.OutOfOrder();
    if (event.sequence() != sequence+1) throw new MessageFailure();
    jdbc.update("""
        UPDATE order_projections SET status=?,sequence_no=?,event_id=?,occurred_at=?,received_at=?
        WHERE order_id=?
        """, event.status(), event.sequence(), event.eventId().toString(),
        Timestamp.from(event.occurredAt()), now, key);
    return true;
  }

  public record Projection(String orderId, String status, int sequence, String eventId) {}

  @Transactional(readOnly=true)
  public Projection find(String id) {
    var rows = jdbc.query("SELECT * FROM order_projections WHERE order_id=?",
        (rs,n) -> new Projection(id, rs.getString("status"), rs.getInt("sequence_no"),
            rs.getString("event_id")), id);
    return rows.isEmpty() ? new Projection(id, "WAITING", 0, null) : rows.getFirst();
  }
}
