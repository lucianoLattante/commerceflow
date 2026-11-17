package dev.lattante.commerceflow.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lattante.commerceflow.catalog.CatalogService;
import dev.lattante.commerceflow.identity.*;
import dev.lattante.commerceflow.inventory.InventoryReservation.Demand;
import dev.lattante.commerceflow.orders.*;
import dev.lattante.commerceflow.shared.Money;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties={"CF_BOOTSTRAP_ADMIN_EMAIL=","CF_BOOTSTRAP_ADMIN_PASSWORD=",
    "commerceflow.messaging.enabled=true","commerceflow.messaging.scheduling-enabled=false",
    "commerceflow.messaging.listener-auto-startup=false",
    "commerceflow.reservations.scheduling-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@Import(KafkaDeliveryIT.TestClockConfiguration.class)
class KafkaDeliveryIT {
  private static final String PASS="local-messaging-test-fixture";
  @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.11")
      .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("messaging_test")
      .withUsername("test").withPassword("test");
  @Container static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka:4.1.1");
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);
    r.add("spring.datasource.password",MYSQL::getPassword);
    r.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);
  }
  static class MutableClock extends Clock {
    volatile Instant current=Instant.parse("2026-01-01T00:00:00Z");
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}
    public Instant instant(){return current;}
    void advance(long seconds){current=current.plusSeconds(seconds);}
  }
  @TestConfiguration static class TestClockConfiguration {
    @Bean @Primary MutableClock messagingClock(){return new MutableClock();}
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired IdentityService identity;
  @Autowired CatalogService catalog;
  @Autowired OrderService orders;
  @Autowired OrderLifecycleService lifecycle;
  @Autowired DispatchStore store;
  @Autowired MessageDispatcher dispatcher;
  @Autowired MessageOperations operations;
  @Autowired KafkaTemplate<String,String> kafka;
  @Autowired KafkaListenerEndpointRegistry registry;
  @Autowired MutableClock clock;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoSpyBean OrderInbox inbox;
  @MockitoSpyBean DeadLetters deadLetters;
  Actor owner,admin;
  String ownerToken,adminToken;

  @BeforeEach void clean() throws Exception {
    registry.getListenerContainer("order-projection").stop();
    // Test-only isolation before deleting DB state: retain no records from the preceding case.
    isolateBrokerOffsets();
    for(String trigger:List.of("reject_projection","reject_sent","reject_enqueue"))
      jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
    for(String table:List.of("message_operator_requests","message_dispatch","message_dead_letters",
        "message_inbox","order_projections","outbox_events","idempotency_requests","order_lines",
        "customer_orders","access_tokens","products","app_users")) jdbc.update("DELETE FROM "+table);
    clock.current=Instant.parse("2026-01-01T00:00:00Z");
    var user=identity.register("customer@example.test",PASS);
    owner=new Actor(user.id(),user.email(),AppUser.Role.CUSTOMER);
    identity.bootstrap("admin@example.test",PASS);
    ownerToken=identity.login(owner.email(),PASS).accessToken();
    adminToken=identity.login("admin@example.test",PASS).accessToken();
    admin=identity.authenticate(adminToken).orElseThrow();
    registry.getListenerContainer("order-projection").start();
    eventually(()->{
      var assigned=registry.getListenerContainer("order-projection").getAssignedPartitions();
      return assigned!=null && assigned.size()==3;
    });
  }
  @AfterEach void stop(){registry.getListenerContainer("order-projection").stop();}

  private void isolateBrokerOffsets() throws Exception {
    try(var brokerAdmin=org.apache.kafka.clients.admin.Admin.create(
        Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))){
      Map<TopicPartition,org.apache.kafka.clients.admin.OffsetSpec> requested=new HashMap<>();
      for(int i=0;i<3;i++)requested.put(new TopicPartition(OrderMessageCodec.TOPIC,i),
          org.apache.kafka.clients.admin.OffsetSpec.latest());
      var ends=brokerAdmin.listOffsets(requested).all().get(10,TimeUnit.SECONDS);
      if(ends.values().stream().anyMatch(v->v.offset()>0)){
        Map<TopicPartition,OffsetAndMetadata> offsets=new HashMap<>();
        ends.forEach((partition,offset)->offsets.put(partition,new OffsetAndMetadata(offset.offset())));
        brokerAdmin.alterConsumerGroupOffsets("commerceflow-order-projection-v1",offsets)
            .all().get(10,TimeUnit.SECONDS);
      }
    }
  }
  private long committedOffset(TopicPartition partition) throws Exception {
    try(var brokerAdmin=org.apache.kafka.clients.admin.Admin.create(
        Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))){
      var offsets=brokerAdmin.listConsumerGroupOffsets("commerceflow-order-projection-v1")
          .partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);
      return offsets.containsKey(partition)?offsets.get(partition).offset():0;
    }
  }

  private void eventually(BooleanSupplier condition) throws Exception {
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
    while(System.nanoTime()<until){if(condition.getAsBoolean())return;Thread.sleep(50);}
    assertThat(condition.getAsBoolean()).as("bounded asynchronous condition").isTrue();
  }
  private long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
  private String order(){
    var product=catalog.create("TEST-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
        "Messaging fixture",new BigDecimal("12.34"),Money.Currency.USD,10);
    return orders.create(owner,"order-"+UUID.randomUUID(),
        new OrderSubmission(List.of(new Demand(UUID.fromString(product.id()),2)))).order().id();
  }
  private String raw(String order){
    return jdbc.queryForObject("SELECT payload FROM message_dispatch WHERE aggregate_id=? AND aggregate_sequence=1",
        String.class,order);
  }
  private String delivery(String order){
    return jdbc.queryForObject("SELECT id FROM message_dispatch WHERE aggregate_id=? AND aggregate_sequence=1",
        String.class,order);
  }
  private String state(String id){return jdbc.queryForObject("SELECT state FROM message_dispatch WHERE id=?",String.class,id);}
  private void drain(){for(int i=0;i<20 && dispatcher.dispatchOnce();i++){}}
  private List<String> topicRecords(String topic,Predicate<String> matches,int required) throws Exception {
    Map<String,Object> p=new HashMap<>();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG,"evidence-"+UUID.randomUUID());
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
    try(var consumer=new KafkaConsumer<String,String>(p)){
      var partitions=List.of(new TopicPartition(topic,0),new TopicPartition(topic,1),new TopicPartition(topic,2));
      consumer.assign(partitions);consumer.seekToBeginning(partitions);
      var result=new ArrayList<String>();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
      while(result.size()<required && System.nanoTime()<until)
        for(var record:consumer.poll(Duration.ofMillis(200)))
          if(record.value()!=null && matches.test(record.value()))result.add(record.value());
      assertThat(result.size()).isGreaterThanOrEqualTo(required);return result;
    }
  }

  @Test void committedOrderAndPaymentTravelThroughRealKafkaIntoProjection() throws Exception {
    String order=order();
    lifecycle.pay(order,owner,"payment-key-01",PaymentDecision.APPROVED);
    assertThat(count("message_dispatch")).isEqualTo(2);
    drain();
    eventually(()->inbox.find(order).sequence()==2);
    assertThat(inbox.find(order).status()).isEqualTo("PAID");
    assertThat(count("message_inbox")).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NOT NULL",Long.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT on_hand FROM products",Integer.class)).isEqualTo(8);
  }

  @Test void brokerAckThenDatabaseFailureRedeliversButAppliesOnce() throws Exception {
    String order=order(),raw=raw(order),id=delivery(order);
    jdbc.execute("CREATE TRIGGER reject_sent BEFORE UPDATE ON message_dispatch FOR EACH ROW"
        +" BEGIN IF NEW.state='SENT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected ack-window failure'; END IF; END");
    try{
      assertThatThrownBy(()->dispatcher.dispatchOnce()).isInstanceOf(RuntimeException.class);
      eventually(()->inbox.find(order).sequence()==1);
      assertThat(state(id)).isEqualTo("IN_FLIGHT");
      assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_events",Boolean.class)).isTrue();
    } finally {jdbc.execute("DROP TRIGGER reject_sent");}
    clock.advance(31);
    assertThat(dispatcher.dispatchOnce()).isTrue();
    assertThat(topicRecords(OrderMessageCodec.TOPIC,s->s.equals(raw),2)).hasSize(2);
    eventually(()->inbox.find(order).sequence()==1);
    assertThat(count("message_inbox")).isEqualTo(1);
    assertThat(state(id)).isEqualTo("SENT");
    assertThat(jdbc.queryForObject("SELECT attempts FROM message_dispatch WHERE id=?",Integer.class,id)).isEqualTo(2);
  }

  @Test void expiredClaimTokensCannotCompleteOrFailANewerLease() throws Exception {
    String order=order();
    var old=store.claim().orElseThrow();
    clock.advance(31);
    var current=store.claim().orElseThrow();
    assertThat(current.id()).isEqualTo(old.id());
    assertThat(current.token()).isNotEqualTo(old.token());
    assertThat(store.complete(old)).isFalse();
    store.failed(old);
    assertThat(state(current.id())).isEqualTo("IN_FLIGHT");
    kafka.send(current.topic(),current.key(),current.payload()).get(10,TimeUnit.SECONDS);
    assertThat(store.complete(current)).isTrue();
    eventually(()->inbox.find(order).sequence()==1);
  }

  @Test void actualUnavailableBrokerExhaustsFiveAttemptsThenAdminCanRetry() throws Exception {
    String order=order(),id=delivery(order);
    Map<String,Object> p=new HashMap<>();
    p.put("bootstrap.servers","127.0.0.1:1");
    p.put("key.serializer",StringSerializer.class);p.put("value.serializer",StringSerializer.class);
    p.put("max.block.ms",250);p.put("request.timeout.ms",500);p.put("delivery.timeout.ms",1000);
    p.put("linger.ms",0);
    var factory=new DefaultKafkaProducerFactory<String,String>(p);
    var unavailable=new KafkaTemplate<String,String>(factory);
    MessageSender failed=(topic,key,payload)->{
      try{unavailable.send(topic,key,payload).get(2,TimeUnit.SECONDS);}
      catch(Exception e){throw new IllegalStateException("synthetic unavailable broker",e);}
    };
    try{
      var worker=new MessageDispatcher(store,failed);
      for(int i=1;i<=5;i++){
        assertThat(worker.dispatchOnce()).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM message_dispatch WHERE id=?",Integer.class,id)).isEqualTo(i);
        if(i<5){assertThat(worker.dispatchOnce()).isFalse();clock.advance(17);}
      }
    } finally {factory.destroy();}
    assertThat(state(id)).isEqualTo("FAILED");
    assertThat(count("message_inbox")).isZero();
    assertThat(orders.find(order,owner).status()).isEqualTo(OrderStatus.RESERVED);
    var retry=operations.retry(id,admin,"operator-retry-key");
    assertThat(operations.retry(id,admin,"operator-retry-key").id()).isEqualTo(retry.id());
    assertThat(count("message_operator_requests")).isEqualTo(1);
    assertThat(retry.retryCycles()).isEqualTo(1);
    drain();eventually(()->inbox.find(order).sequence()==1);
  }

  @Test void poisonRecordIsDurablyCapturedAndPublishedToKafkaDlq() throws Exception {
    String key=UUID.randomUUID().toString();
    kafka.send(OrderMessageCodec.TOPIC,key,"not-json").get(10,TimeUnit.SECONDS);
    eventually(()->count("message_dead_letters")==1);
    assertThat(count("message_inbox")).isZero();
    String id=operations.deadLetters(10).getFirst().id();
    drain();
    var values=topicRecords(OrderMessageCodec.DLQ,s->s.contains(id),1);
    assertThat(json.readTree(values.getFirst()).get("originalPayload").asText()).isEqualTo("not-json");
    assertThat(operations.deadLetters(10).getFirst().replayCount()).isZero();
  }

  @Test void consumerRollbackRetriesThenExactPayloadReplaySucceedsAfterRepair() throws Exception {
    String order=order(),raw=raw(order);
    jdbc.execute("CREATE TRIGGER reject_projection BEFORE UPDATE ON order_projections FOR EACH ROW"
        +" SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected projection failure'");
    try{
      drain();
      eventually(()->count("message_dead_letters")==1);
      verify(inbox,times(3)).apply(order,raw);
      assertThat(count("message_inbox")).isZero();
      assertThat(count("order_projections")).isZero();
    } finally {jdbc.execute("DROP TRIGGER reject_projection");}
    String id=operations.deadLetters(10).getFirst().id();
    var replay=operations.replay(id,admin,"operator-replay-key");
    assertThat(operations.replay(id,admin,"operator-replay-key").id()).isEqualTo(replay.id());
    assertThat(count("message_operator_requests")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT payload FROM message_dispatch WHERE id=?",String.class,replay.id())).isEqualTo(raw);
    drain();eventually(()->inbox.find(order).sequence()==1);
    assertThat(count("message_inbox")).isEqualTo(1);
    assertThat(operations.deadLetters(10).getFirst().replayCount()).isEqualTo(1);
  }

  @Test void sameEventIdWithChangedContentGoesToDlqWithoutChangingProjection() throws Exception {
    String order=order();drain();eventually(()->inbox.find(order).sequence()==1);
    var changed=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(raw(order));
    ((com.fasterxml.jackson.databind.node.ObjectNode)changed.get("payload")).put("customerId",UUID.randomUUID().toString());
    kafka.send(OrderMessageCodec.TOPIC,order,changed.toString()).get(10,TimeUnit.SECONDS);
    eventually(()->count("message_dead_letters")==1);
    assertThat(count("message_inbox")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT customer_id FROM order_projections",String.class)).isEqualTo(owner.id());
  }

  @Test void inFlightReservationMessageBlocksItsTerminalMessageUntilAckIsRecorded() throws Exception {
    String order=order();lifecycle.pay(order,owner,"payment-key-01",PaymentDecision.APPROVED);
    var first=store.claim().orElseThrow();
    assertThat(json.readTree(first.payload()).get("sequence").asInt()).isEqualTo(1);
    assertThat(store.claim()).isEmpty();
    kafka.send(first.topic(),first.key(),first.payload()).get(10,TimeUnit.SECONDS);
    store.complete(first);
    var second=store.claim().orElseThrow();
    assertThat(json.readTree(second.payload()).get("sequence").asInt()).isEqualTo(2);
    kafka.send(second.topic(),second.key(),second.payload()).get(10,TimeUnit.SECONDS);
    store.complete(second);
    eventually(()->inbox.find(order).sequence()==2);
    assertThat(inbox.find(order).status()).isEqualTo("PAID");
  }

  @Test void simultaneousDuplicateConsumersCommitOneInboxAndOneProjection() throws Exception {
    String order=order(),raw=raw(order);
    try(var pool=Executors.newFixedThreadPool(8)){
      CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
      for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return inbox.apply(order,raw);}));
      start.countDown();int applied=0;
      for(var result:futures)if(result.get(20,TimeUnit.SECONDS))applied++;
      assertThat(applied).isEqualTo(1);
    }
    assertThat(count("message_inbox")).isEqualTo(1);
    assertThat(inbox.find(order).sequence()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT reserved FROM products",Integer.class)).isEqualTo(2);
  }

  @Test void dlqCaptureAndItsTransportOutboxRollbackTogetherAndOffsetIsRetried() throws Exception {
    jdbc.execute("CREATE TRIGGER reject_enqueue BEFORE INSERT ON message_dispatch FOR EACH ROW"
        +" SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected DLQ enqueue failure'");
    org.apache.kafka.clients.producer.RecordMetadata poison;
    try{
      poison=kafka.send(OrderMessageCodec.TOPIC,UUID.randomUUID().toString(),"poison-fixture")
          .get(10,TimeUnit.SECONDS).getRecordMetadata();
      eventually(()->org.mockito.Mockito.mockingDetails(deadLetters).getInvocations().stream()
          .anyMatch(i->i.getMethod().getName().equals("capture")));
      assertThat(count("message_dead_letters")).isZero();
      assertThat(count("message_dispatch")).isZero();
      assertThat(committedOffset(new TopicPartition(poison.topic(),poison.partition())))
          .isLessThanOrEqualTo(poison.offset());
    } finally {jdbc.execute("DROP TRIGGER reject_enqueue");}
    eventually(()->count("message_dead_letters")==1);
    assertThat(count("message_dispatch")).isEqualTo(1);
    final var recovered=poison;
    eventually(()->{
      try{return committedOffset(new TopicPartition(recovered.topic(),recovered.partition()))>recovered.offset();}
      catch(Exception e){throw new IllegalStateException(e);}
    });
    drain();
  }

  @Test void operatorActionsRequireAdminAndProjectionReadRequiresOwnership() throws Exception {
    String order=order();
    mvc.perform(get("/api/orders/"+order+"/projection").header("Authorization","Bearer "+ownerToken))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING"));
    var stranger=identity.register("stranger@example.test",PASS);
    String strangerToken=identity.login(stranger.email(),PASS).accessToken();
    mvc.perform(get("/api/orders/"+order+"/projection").header("Authorization","Bearer "+strangerToken))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/admin/messaging/dead-letters")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/messaging/dead-letters").header("Authorization","Bearer "+ownerToken))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/messaging/dead-letters/"+"a".repeat(64)+"/replay")
        .header("Authorization","Bearer "+ownerToken).header("Idempotency-Key","operator-key"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/messaging/dead-letters").header("Authorization","Bearer "+adminToken))
        .andExpect(status().isOk()).andExpect(content().json("[]"));
    mvc.perform(get("/api/admin/messaging/failures").param("size","101").header("Authorization","Bearer "+adminToken))
        .andExpect(status().isBadRequest());
    assertThatThrownBy(()->operations.replay("a".repeat(64),owner,"operator-key"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test void operatorReplayIsBoundedIdempotentAuditedAndCannotEditPoisonPayload() {
    String id=deadLetters.capture(OrderMessageCodec.TOPIC,0,999,"key","immutable-fixture");
    String same=operations.replay(id,admin,"same-replay-key").id();
    assertThat(operations.replay(id,admin,"same-replay-key").id()).isEqualTo(same);
    operations.replay(id,admin,"second-replay-key");operations.replay(id,admin,"third-replay-key");
    assertThatThrownBy(()->operations.replay(id,admin,"fourth-replay-key")).isInstanceOf(MessageFailure.Conflict.class);
    assertThat(count("message_operator_requests")).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_operator_requests WHERE actor_id=?",Long.class,admin.id())).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_dispatch WHERE kind='REPLAY' AND payload='immutable-fixture'",Long.class)).isEqualTo(3);
    String tooBig=deadLetters.capture(OrderMessageCodec.TOPIC,0,1000,"key","x".repeat(OrderMessageCodec.MAX_BYTES+1));
    assertThatThrownBy(()->operations.replay(tooBig,admin,"oversized-replay-key")).isInstanceOf(MessageFailure.Conflict.class);
    assertThat(jdbc.queryForObject("SELECT payload IS NULL FROM message_dead_letters WHERE id=?",Boolean.class,tooBig)).isTrue();
  }

  @Test void outOfOrderConsumerEventCanBeReplayedAfterItsPredecessor() throws Exception {
    String order=order();lifecycle.pay(order,owner,"payment-key-01",PaymentDecision.APPROVED);
    String terminal=jdbc.queryForObject("SELECT payload FROM message_dispatch WHERE aggregate_id=? AND aggregate_sequence=2",String.class,order);
    kafka.send(OrderMessageCodec.TOPIC,order,terminal).get(10,TimeUnit.SECONDS);
    eventually(()->count("message_dead_letters")==1);
    assertThat(count("message_inbox")).isZero();
    kafka.send(OrderMessageCodec.TOPIC,order,raw(order)).get(10,TimeUnit.SECONDS);
    eventually(()->inbox.find(order).sequence()==1);
    String dlq=operations.deadLetters(10).getFirst().id();
    String replay=operations.replay(dlq,admin,"replay-after-predecessor").id();
    // Source publications may also send the same immutable events; inbox deduplication handles both.
    drain();eventually(()->inbox.find(order).sequence()==2);
    assertThat(inbox.find(order).status()).isEqualTo("PAID");
    assertThat(count("message_inbox")).isEqualTo(2);
  }

  @Test void fifthAbandonedLeaseBecomesDurablyFailedInsteadOfDisappearing() {
    String order=order(),id=delivery(order);
    for(int i=1;i<=5;i++){
      var lease=store.claim().orElseThrow();
      assertThat(lease.attempt()).isEqualTo(i);
      clock.advance(31);
    }
    assertThat(store.claim()).isEmpty();
    assertThat(state(id)).isEqualTo("FAILED");
    assertThat(operations.failures(10).getFirst().errorCode()).isEqualTo("LEASE_EXHAUSTED");
    for(int cycle=1;cycle<=3;cycle++){
      operations.retry(id,admin,"retry-cycle-"+cycle);
      jdbc.update("UPDATE message_dispatch SET state='FAILED' WHERE id=?",id);
    }
    assertThatThrownBy(()->operations.retry(id,admin,"retry-cycle-four")).isInstanceOf(MessageFailure.Conflict.class);
  }

  @Test void multiplePublishersClaimDistinctRowsWithoutSimultaneousDuplicateDelivery() throws Exception {
    List<String> ids=new ArrayList<>();for(int i=0;i<8;i++)ids.add(order());
    try(var pool=Executors.newFixedThreadPool(8)){
      CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> work=new ArrayList<>();
      for(int i=0;i<8;i++)work.add(pool.submit(()->{start.await();return dispatcher.dispatchOnce();}));
      start.countDown();
      for(var result:work)assertThat(result.get(20,TimeUnit.SECONDS)).isTrue();
    }
    eventually(()->count("message_inbox")==8);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_dispatch WHERE state='SENT' AND attempts=1",Long.class)).isEqualTo(8);
    for(String id:ids)assertThat(inbox.find(id).sequence()).isEqualTo(1);
  }

  @Test void repeatedConcurrentRecoveryOfOneBrokerOffsetCreatesOneDlqTransport() throws Exception {
    try(var pool=Executors.newFixedThreadPool(8)){
      List<Callable<String>> work=new ArrayList<>();
      for(int i=0;i<8;i++)work.add(()->deadLetters.capture(OrderMessageCodec.TOPIC,1,12345,"key","immutable"));
      Set<String> ids=new HashSet<>();
      for(var result:pool.invokeAll(work))ids.add(result.get(20,TimeUnit.SECONDS));
      assertThat(ids).hasSize(1);
    }
    assertThat(count("message_dead_letters")).isEqualTo(1);
    assertThat(count("message_dispatch")).isEqualTo(1);
  }
}
