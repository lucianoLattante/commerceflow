package dev.lattante.commerceflow.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessagingTest {
  private final ObjectMapper json=new ObjectMapper().findAndRegisterModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final OrderMessageCodec codec=new OrderMessageCodec(json);
  private final UUID order=UUID.randomUUID(),event=UUID.randomUUID();
  private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
  private String wire() throws Exception {
    return codec.encode(event,order.toString(),"ORDER_RESERVED",now,json.writeValueAsString(Map.of(
        "eventId",event,"orderId",order,"customerId",UUID.randomUUID(),"occurredAt",now.toString())));
  }
  @Test void validEnvelopeRetainsRoutingAndIdentity() throws Exception {
    var decoded=codec.decode(order.toString(),wire());
    assertThat(decoded.eventId()).isEqualTo(event);
    assertThat(decoded.sequence()).isEqualTo(1);
    assertThat(decoded.status()).isEqualTo("RESERVED");
  }
  @ParameterizedTest
  @ValueSource(strings={"version","sequence","eventId","orderId","type","occurredAt","payload"})
  void invalidEnvelopeFieldsAreRejected(String field) throws Exception {
    ObjectNode raw=(ObjectNode)json.readTree(wire());
    raw.remove(field);
    assertThatThrownBy(()->codec.decode(order.toString(),raw.toString())).isInstanceOf(MessageFailure.class);
  }
  @Test void recordKeyAndEmbeddedIdentityMustMatch() throws Exception {
    String raw=wire();
    assertThatThrownBy(()->codec.decode(UUID.randomUUID().toString(),raw)).isInstanceOf(MessageFailure.class);
    ObjectNode changed=(ObjectNode)json.readTree(raw);
    ((ObjectNode)changed.get("payload")).put("eventId",UUID.randomUUID().toString());
    assertThatThrownBy(()->codec.decode(order.toString(),changed.toString())).isInstanceOf(MessageFailure.class);
  }
  @Test void malformedNullAndOversizedMessagesAreRejected() {
    for(String raw:Arrays.asList(null,"not-json","x".repeat(OrderMessageCodec.MAX_BYTES+1)))
      assertThatThrownBy(()->codec.decode(order.toString(),raw)).isInstanceOf(MessageFailure.class);
  }
  @Test void publisherBackoffIsBounded() {
    assertThat(java.util.stream.IntStream.rangeClosed(1,5).mapToLong(DispatchStore::backoffSeconds).toArray())
        .containsExactly(1,2,4,8,16);
    assertThatThrownBy(()->DispatchStore.backoffSeconds(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->DispatchStore.backoffSeconds(6)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void brokerFailureSchedulesRetryButDoesNotMarkBusinessEventSent() {
    DispatchStore store=mock(DispatchStore.class);MessageSender sender=mock(MessageSender.class);
    var lease=new DispatchStore.Lease("id","ORDER","source","topic","key","payload",1,"token");
    when(store.claim()).thenReturn(Optional.of(lease));
    doThrow(new IllegalStateException("synthetic")).when(sender).send("topic","key","payload");
    new MessageDispatcher(store,sender).dispatchOnce();
    verify(store).failed(lease);
    verify(store,never()).complete(any());
  }
  @Test void failureAfterBrokerAckRetainsTheLeaseForCrashRecovery() {
    DispatchStore store=mock(DispatchStore.class);MessageSender sender=mock(MessageSender.class);
    var lease=new DispatchStore.Lease("id","ORDER","source","topic","key","payload",1,"token");
    when(store.claim()).thenReturn(Optional.of(lease));
    when(store.complete(lease)).thenThrow(new IllegalStateException("synthetic database failure"));
    assertThatThrownBy(()->new MessageDispatcher(store,sender).dispatchOnce()).isInstanceOf(IllegalStateException.class);
    verify(sender).send("topic","key","payload");
    verify(store,never()).failed(any());
  }

  @ParameterizedTest
  @ValueSource(strings={"1.5","\"1\"","null","2147483648"})
  void protocolVersionDoesNotCoerceStringsOrFractionalNumbers(String version) throws Exception {
    ObjectNode raw=(ObjectNode)json.readTree(wire());
    raw.set("version",json.readTree(version));
    assertThatThrownBy(()->codec.decode(order.toString(),raw.toString())).isInstanceOf(MessageFailure.class);
  }
}
