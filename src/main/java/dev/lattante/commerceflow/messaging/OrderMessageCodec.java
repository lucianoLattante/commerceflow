package dev.lattante.commerceflow.messaging;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class OrderMessageCodec {
  public static final String TOPIC = "commerceflow.orders.v1";
  public static final String DLQ = TOPIC + ".dlq";
  public static final int MAX_BYTES = 262144;
  private static final Set<String> TYPES = Set.of(
      "ORDER_RESERVED", "ORDER_PAID", "ORDER_PAYMENT_DECLINED", "ORDER_CANCELLED", "ORDER_EXPIRED");
  private final ObjectMapper json;

  public OrderMessageCodec(ObjectMapper json) { this.json = json; }

  public record Message(UUID eventId, UUID orderId, String type, int version, int sequence,
      Instant occurredAt, JsonNode payload) {
    public String status() { return type.substring("ORDER_".length()); }
    public String customerId() { return payload.path("customerId").asText(); }
  }

  public String encode(UUID id, String orderId, String type, Instant time, String payload) {
    try {
      return json.writeValueAsString(new Message(id, UUID.fromString(orderId), type, 1,
          type.equals("ORDER_RESERVED") ? 1 : 2, time, json.readTree(payload)));
    } catch (Exception invalid) { throw new IllegalStateException("Cannot encode outbox event", invalid); }
  }

  public Message decode(String key, String raw) {
    try {
      if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new MessageFailure();
      Message event = json.readValue(raw, Message.class);
      if (event.eventId() == null || event.orderId() == null || event.occurredAt() == null
          || !TYPES.contains(event.type()) || event.version() != 1
          || event.sequence() != (event.type().equals("ORDER_RESERVED") ? 1 : 2)
          || !event.orderId().toString().equals(key) || event.payload() == null
          || !event.payload().isObject()) throw new MessageFailure();
      JsonNode p = event.payload();
      if (!event.eventId().toString().equals(p.path("eventId").asText())
          || !event.orderId().toString().equals(p.path("orderId").asText())
          || !UUID.fromString(event.customerId()).toString().equals(event.customerId())
          || !event.occurredAt().equals(Instant.parse(p.path("occurredAt").asText()))
          || (event.sequence() == 2 && !event.status().equals(p.path("status").asText())))
        throw new MessageFailure();
      return event;
    } catch (Exception invalid) { throw new MessageFailure(); }
  }
}
