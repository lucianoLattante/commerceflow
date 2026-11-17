package dev.lattante.commerceflow.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lattante.commerceflow.orders.OrderEventSink;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class JdbcOrderOutbox implements OrderEventSink {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcOrderOutbox(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void orderReserved(ReservedOrder event) {
    append(event.eventId(), event.orderId(), "ORDER_RESERVED", event, event.occurredAt());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void orderCompleted(CompletedOrder event) {
    append(event.eventId(), event.orderId(), event.type(), event, event.occurredAt());
  }

  private void append(java.util.UUID id, String orderId, String type, Object event,
      java.time.Instant occurredAt) {
    String payload;
    try {
      payload = json.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize order event", e);
    }
    jdbc.update(
        """
        INSERT INTO outbox_events(id,order_id,event_type,event_version,payload,occurred_at)
        VALUES(?,?,?,1,?,?)
        """,
        id.toString(),
        orderId,
        type,
        payload,
        Timestamp.from(occurredAt));
  }
}
