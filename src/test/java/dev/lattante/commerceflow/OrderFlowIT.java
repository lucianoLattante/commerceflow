package dev.lattante.commerceflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.lattante.commerceflow.identity.IdentityService;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(
    properties = {
      "CF_BOOTSTRAP_ADMIN_EMAIL=",
      "CF_BOOTSTRAP_ADMIN_PASSWORD=",
      "commerceflow.reservations.scheduling-enabled=false"
    })
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability(
    tracing = false)
@AutoConfigureMockMvc
@Testcontainers
class OrderFlowIT {
  private static final String ADMIN = "admin@example.test", PASS = "local-only-test-fixture";

  // Disposable fault-injection database only; Compose does not enable this setting.
  @Container
  static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.4.11")
          .withCommand("--log-bin-trust-function-creators=1")
          .withDatabaseName("orders_test")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired IdentityService identity;
  @Autowired io.micrometer.core.instrument.MeterRegistry transitionMetrics;
  @Autowired dev.lattante.commerceflow.orders.OrderLifecycleService lifecycle;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  java.time.Clock lifecycleClock;

  private String admin;
  private Customer owner;

  private record Customer(String id, String token) {}

  private record Reply(int status, JsonNode body, String replayed) {}

  @BeforeEach
  void clean() throws Exception {
    org.mockito.Mockito.when(lifecycleClock.instant())
        .thenReturn(java.time.Instant.parse("2026-01-01T00:00:00.123456789Z"));
    jdbc.execute("DROP TRIGGER IF EXISTS reject_product_audit");
    jdbc.execute("DROP TRIGGER IF EXISTS reject_outbox");
    jdbc.execute("DROP TRIGGER IF EXISTS reject_dispatch");
    jdbc.execute("DROP TRIGGER IF EXISTS reject_audit");
    for (String table :
        List.of(
            "product_change_audit",
            "order_operation_audit",
            "message_operator_requests",
            "message_dead_letters",
            "message_inbox",
            "order_projections",
            "message_dispatch",
            "outbox_events",
            "idempotency_requests",
            "order_lines",
            "customer_orders",
            "access_tokens",
            "products",
            "app_users")) jdbc.update("DELETE FROM " + table);
    identity.bootstrap(ADMIN, PASS);
    admin = login(ADMIN);
    owner = customer("customer@example.test");
  }

  private String body(Object o) throws Exception {
    return json.writeValueAsString(o);
  }

  private String login(String email) throws Exception {
    return json.readTree(
            mvc.perform(
                    post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", PASS))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("accessToken")
        .asText();
  }

  private Customer customer(String email) throws Exception {
    JsonNode registered =
        json.readTree(
            mvc.perform(
                    post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", PASS))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return new Customer(registered.get("id").asText(), login(email));
  }

  private String product(String sku, int stock) throws Exception {
    return product(sku, stock, "USD", "12.34");
  }

  private String product(String sku, int stock, String currency, String amount) throws Exception {
    return json.readTree(
            mvc.perform(
                    post("/api/products")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            body(
                                Map.of(
                                    "sku",
                                    sku,
                                    "name",
                                    "Snapshot " + sku,
                                    "amount",
                                    amount,
                                    "currency",
                                    currency,
                                    "stock",
                                    stock))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("id")
        .asText();
  }

  private Map<String, Object> item(String id, int quantity) {
    return Map.of("productId", id, "quantity", quantity);
  }

  private List<Map<String, Object>> items(String id, int quantity) {
    return List.of(item(id, quantity));
  }

  private ResultActions submit(String access, String key, List<Map<String, Object>> items)
      throws Exception {
    return mvc.perform(
        post("/api/orders")
            .header("Authorization", "Bearer " + access)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(Map.of("items", items))));
  }

  private Reply reply(String access, String key, List<Map<String, Object>> items) throws Exception {
    var response = submit(access, key, items).andReturn().getResponse();
    return new Reply(
        response.getStatus(),
        json.readTree(response.getContentAsString()),
        response.getHeader("Idempotency-Replayed"));
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private int reserved(String id) {
    return jdbc.queryForObject("SELECT reserved FROM products WHERE id=?", Integer.class, id);
  }

  private void nothingCommitted() {
    for (String table :
        List.of("customer_orders", "order_lines", "idempotency_requests", "outbox_events"))
      assertThat(count(table)).as(table).isZero();
  }

  @Test
  void createsAnOwnedOrderAndOneDurableEventAtomically() throws Exception {
    String id = product("SKU-1", 10);
    Reply response = reply(owner.token(), "order-key-0001", items(id, 3));
    assertThat(response.status()).isEqualTo(201);
    assertThat(response.replayed()).isEqualTo("false");
    assertThat(response.body().get("customerId").asText()).isEqualTo(owner.id());
    assertThat(response.body().get("status").asText()).isEqualTo("RESERVED");
    assertThat(response.body().get("total").decimalValue()).isEqualByComparingTo("37.02");
    assertThat(response.body().get("items").get(0).get("unitAmount").decimalValue())
        .isEqualByComparingTo("12.34");
    assertThat(reserved(id)).isEqualTo(3);
    for (String table :
        List.of("customer_orders", "order_lines", "idempotency_requests", "outbox_events"))
      assertThat(count(table)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT on_hand FROM products WHERE id=?", Integer.class, id))
        .isEqualTo(10);
    assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_events", Boolean.class))
        .isTrue();
    assertThat(jdbc.queryForObject("SELECT event_version FROM outbox_events", Integer.class))
        .isEqualTo(1);
    JsonNode payload =
        json.readTree(jdbc.queryForObject("SELECT payload FROM outbox_events", String.class));
    assertThat(payload.get("orderId").asText()).isEqualTo(response.body().get("id").asText());
    assertThat(payload.get("customerId").asText()).isEqualTo(owner.id());
  }

  @Test
  void retryReturnsTheOriginalOrderWithoutReservingAgain() throws Exception {
    String id = product("SKU-1", 10);
    Reply first = reply(owner.token(), "retry-key-0001", items(id, 2));
    Reply repeated = reply(owner.token(), "retry-key-0001", items(id, 2));
    assertThat(first.status()).isEqualTo(201);
    assertThat(repeated.status()).isEqualTo(200);
    assertThat(repeated.replayed()).isEqualTo("true");
    assertThat(repeated.body()).isEqualTo(first.body());
    assertThat(reserved(id)).isEqualTo(2);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void reorderingItemsIsAnEquivalentRetry() throws Exception {
    String a = product("A", 10), b = product("B", 10);
    Reply first = reply(owner.token(), "reorder-key-01", List.of(item(a, 2), item(b, 1)));
    Reply second = reply(owner.token(), "reorder-key-01", List.of(item(b, 1), item(a, 2)));
    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.body()).isEqualTo(first.body());
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void changedPayloadConflictsWithoutChangingStock() throws Exception {
    String id = product("A", 10);
    assertThat(reply(owner.token(), "conflict-key-1", items(id, 1)).status()).isEqualTo(201);
    submit(owner.token(), "conflict-key-1", items(id, 2))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("idempotency_conflict"));
    assertThat(reserved(id)).isEqualTo(1);
    assertThat(count("customer_orders")).isEqualTo(1);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void keyIsScopedToTheAuthenticatedCustomer() throws Exception {
    String id = product("A", 10);
    Customer other = customer("other@example.test");
    Reply a = reply(owner.token(), "shared-key-01", items(id, 1)),
        b = reply(other.token(), "shared-key-01", items(id, 1));
    assertThat(a.status()).isEqualTo(201);
    assertThat(b.status()).isEqualTo(201);
    assertThat(a.body().get("id")).isNotEqualTo(b.body().get("id"));
    assertThat(count("idempotency_requests")).isEqualTo(2);
    assertThat(reserved(id)).isEqualTo(2);
  }

  @Test
  void snapshotSurvivesLaterCatalogChangesAndRetry() throws Exception {
    String id = product("A", 10);
    Reply original = reply(owner.token(), "snapshot-key-1", items(id, 2));
    jdbc.update("UPDATE products SET name='Changed',amount=99.99 WHERE id=?", id);
    mvc.perform(
            get("/api/orders/" + original.body().get("id").asText())
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(24.68))
        .andExpect(jsonPath("$.items[0].name").value("Snapshot A"))
        .andExpect(jsonPath("$.items[0].unitAmount").value(12.34));
    assertThat(reply(owner.token(), "snapshot-key-1", items(id, 2)).body())
        .isEqualTo(original.body());
  }

  @Test
  void nonOwnerCannotReadAnOrderButAdministratorCan() throws Exception {
    String id = product("A", 10);
    Reply created = reply(owner.token(), "owner-key-001", items(id, 1));
    Customer stranger = customer("stranger@example.test");
    String url = "/api/orders/" + created.body().get("id").asText();
    mvc.perform(get(url).header("Authorization", "Bearer " + stranger.token()))
        .andExpect(status().isNotFound());
    mvc.perform(get(url).header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
    mvc.perform(
            get("/api/orders/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + stranger.token()))
        .andExpect(status().isNotFound());
  }

  @Test
  void orderListingDoesNotExposeOtherCustomers() throws Exception {
    String id = product("A", 10);
    Customer other = customer("other@example.test");
    assertThat(reply(owner.token(), "list-key-0001", items(id, 1)).status()).isEqualTo(201);
    assertThat(reply(owner.token(), "list-key-0002", items(id, 1)).status()).isEqualTo(201);
    assertThat(reply(other.token(), "list-key-0003", items(id, 1)).status()).isEqualTo(201);
    mvc.perform(
            get("/api/orders")
                .header("Authorization", "Bearer " + owner.token())
                .param("size", "1")
                .param("status", "RESERVED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(1));
    mvc.perform(get("/api/orders").header("Authorization", "Bearer " + other.token()))
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/api/orders").header("Authorization", "Bearer " + admin))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void callerCannotChooseAnotherOrderOwner() throws Exception {
    String id = product("A", 10);
    Customer other = customer("other@example.test");
    mvc.perform(
            post("/api/orders")
                .header("Authorization", "Bearer " + owner.token())
                .header("Idempotency-Key", "owner-input-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("customerId", other.id(), "items", items(id, 1)))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.customerId").value(owner.id()));
  }

  @Test
  void anonymousOrderCreationHasNoSideEffects() throws Exception {
    String id = product("A", 10);
    mvc.perform(
            post("/api/orders")
                .header("Idempotency-Key", "anonymous-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("items", items(id, 1)))))
        .andExpect(status().isUnauthorized());
    nothingCommitted();
    assertThat(reserved(id)).isZero();
  }

  @Test
  void missingIdempotencyHeaderIsAClientError() throws Exception {
    String id = product("A", 10);
    mvc.perform(
            post("/api/orders")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("items", items(id, 1)))))
        .andExpect(status().isBadRequest());
    nothingCommitted();
    assertThat(reserved(id)).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"short", "invalid key", ""})
  void malformedIdempotencyKeysHaveNoSideEffects(String key) throws Exception {
    String id = product("A", 10);
    submit(owner.token(), key, items(id, 1)).andExpect(status().isBadRequest());
    nothingCommitted();
    assertThat(reserved(id)).isZero();
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1001})
  void invalidQuantitiesHaveNoSideEffects(int quantity) throws Exception {
    String id = product("A", 10);
    submit(owner.token(), "quantity-key-1", items(id, quantity)).andExpect(status().isBadRequest());
    nothingCommitted();
    assertThat(reserved(id)).isZero();
  }

  @Test
  void duplicateProductsAreRejected() throws Exception {
    String id = product("A", 10);
    submit(owner.token(), "duplicate-key", List.of(item(id, 1), item(id, 1)))
        .andExpect(status().isBadRequest());
    nothingCommitted();
  }

  @Test
  void emptyAndNullItemsAreRejected() throws Exception {
    submit(owner.token(), "empty-key-001", List.of()).andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/orders")
                .header("Authorization", "Bearer " + owner.token())
                .header("Idempotency-Key", "null-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":null}"))
        .andExpect(status().isBadRequest());
    nothingCommitted();
  }

  @Test
  void moreThanFiftyItemsAreRejectedBeforeReservation() throws Exception {
    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = 0; i < 51; i++) items.add(item(UUID.randomUUID().toString(), 1));
    submit(owner.token(), "too-many-key", items).andExpect(status().isBadRequest());
    nothingCommitted();
  }

  @Test
  void missingProductRollsBackAnEarlierReservation() throws Exception {
    String id = product("A", 10);
    String missing = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    submit(owner.token(), "missing-key-01", List.of(item(id, 1), item(missing, 1)))
        .andExpect(status().isNotFound());
    nothingCommitted();
    assertThat(reserved(id)).isZero();
  }

  @Test
  void unavailableSecondProductRollsBackTheFirst() throws Exception {
    List<String> ids = new ArrayList<>(List.of(product("A", 10), product("B", 10)));
    ids.sort(String::compareTo);
    jdbc.update("UPDATE products SET on_hand=0 WHERE id=?", ids.get(1));
    submit(owner.token(), "partial-key-01", List.of(item(ids.get(1), 1), item(ids.get(0), 1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("insufficient_stock"));
    nothingCommitted();
    for (String id : ids) assertThat(reserved(id)).isZero();
  }

  @Test
  void mixedCurrencyFailureRollsBackStockAndClaim() throws Exception {
    String a = product("A", 10, "USD", "1.20"), b = product("B", 10, "ARS", "2.00");
    submit(owner.token(), "currency-key1", List.of(item(a, 1), item(b, 1)))
        .andExpect(status().isBadRequest());
    nothingCommitted();
    assertThat(reserved(a)).isZero();
    assertThat(reserved(b)).isZero();
  }

  @Test
  void aFailedRequestDoesNotConsumeItsIdempotencyKey() throws Exception {
    String id = product("A", 1);
    submit(owner.token(), "recover-key-01", items(id, 2)).andExpect(status().isConflict());
    nothingCommitted();
    assertThat(reply(owner.token(), "recover-key-01", items(id, 1)).status()).isEqualTo(201);
    assertThat(reserved(id)).isEqualTo(1);
  }

  @Test
  void outboxFailureRollsBackOrderStockLinesAndClaim() throws Exception {
    String id = product("A", 10);
    jdbc.execute(
        "CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox_events FOR EACH ROW SIGNAL SQLSTATE"
            + " '45000' SET MESSAGE_TEXT='injected test failure'");
    try {
      submit(owner.token(), "outbox-key-01", items(id, 2))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.code").value("internal_error"));
      nothingCommitted();
      assertThat(reserved(id)).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER reject_outbox");
    }
    assertThat(reply(owner.token(), "outbox-key-01", items(id, 2)).status()).isEqualTo(201);
    assertThat(reserved(id)).isEqualTo(2);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void idempotencyKeysAreStoredOnlyAsDigests() throws Exception {
    String id = product("A", 10);
    String key = "private-idempotency-key";
    assertThat(reply(owner.token(), key, items(id, 1)).status()).isEqualTo(201);
    assertThat(jdbc.queryForObject("SELECT key_hash FROM idempotency_requests", String.class))
        .matches("[0-9a-f]{64}")
        .isNotEqualTo(key);
    assertThat(jdbc.queryForObject("SELECT request_hash FROM idempotency_requests", String.class))
        .matches("[0-9a-f]{64}");
  }

  private List<Reply> concurrently(int count, IntFunction<Reply> work) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(count);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Reply>> pending = new ArrayList<>();
    try {
      for (int i = 0; i < count; i++) {
        final int index = i;
        pending.add(
            pool.submit(
                () -> {
                  start.await();
                  return work.apply(index);
                }));
      }
      start.countDown();
      List<Reply> results = new ArrayList<>();
      for (Future<Reply> future : pending) results.add(future.get(30, TimeUnit.SECONDS));
      return results;
    } finally {
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Reply concurrentReply(String key, List<Map<String, Object>> lines) {
    try {
      return reply(owner.token(), key, lines);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void simultaneousRetriesCreateExactlyOneOrderAndEvent() throws Exception {
    String id = product("A", 10);
    List<Reply> replies =
        concurrently(8, i -> concurrentReply("concurrent-same-key", items(id, 2)));
    assertThat(replies.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
    assertThat(replies.stream().filter(r -> r.status() == 200).count()).isEqualTo(7);
    assertThat(replies.stream().map(r -> r.body().get("id").asText()).distinct().count())
        .isEqualTo(1);
    assertThat(reserved(id)).isEqualTo(2);
    for (String table : List.of("customer_orders", "idempotency_requests", "outbox_events"))
      assertThat(count(table)).isEqualTo(1);
  }

  @Test
  void competingOrdersCannotReserveMoreThanTheRealStock() throws Exception {
    String id = product("A", 7);
    List<Reply> replies = concurrently(20, i -> concurrentReply("compete-key-" + i, items(id, 1)));
    assertThat(replies.stream().filter(r -> r.status() == 201).count()).isEqualTo(7);
    assertThat(replies.stream().filter(r -> r.status() == 409).count()).isEqualTo(13);
    assertThat(reserved(id)).isEqualTo(7);
    for (String table : List.of("customer_orders", "idempotency_requests", "outbox_events"))
      assertThat(count(table)).isEqualTo(7);
  }

  @Test
  void reverseInputOrderingUsesAConsistentDatabaseLockOrder() throws Exception {
    String a = product("A", 8), b = product("B", 8);
    List<Reply> replies =
        concurrently(
            8,
            i ->
                concurrentReply(
                    "reverse-key-" + i,
                    i % 2 == 0
                        ? List.of(item(a, 1), item(b, 1))
                        : List.of(item(b, 1), item(a, 1))));
    assertThat(replies).allMatch(r -> r.status() == 201);
    assertThat(reserved(a)).isEqualTo(8);
    assertThat(reserved(b)).isEqualTo(8);
    assertThat(count("outbox_events")).isEqualTo(8);
  }

  @Test
  void invalidOrderPaginationReturns400() throws Exception {
    mvc.perform(
            get("/api/orders")
                .header("Authorization", "Bearer " + owner.token())
                .param("size", "101"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/orders")
                .header("Authorization", "Bearer " + owner.token())
                .param("status", "UNKNOWN"))
        .andExpect(status().isBadRequest());
  }

  private String newOrder(String product, int quantity) throws Exception {
    return reply(owner.token(), "create-" + UUID.randomUUID(), items(product, quantity))
        .body()
        .get("id")
        .asText();
  }

  private ResultActions payment(String access, String order, String key, String decision)
      throws Exception {
    return mvc.perform(
        post("/api/orders/" + order + "/simulated-payment")
            .header("Authorization", "Bearer " + access)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(Map.of("decision", decision))));
  }

  private ResultActions cancel(String access, String order) throws Exception {
    return mvc.perform(
        post("/api/orders/" + order + "/cancel").header("Authorization", "Bearer " + access));
  }

  private void stockIs(String product, int onHand, int reserved) {
    assertThat(
            jdbc.queryForObject("SELECT on_hand FROM products WHERE id=?", Integer.class, product))
        .isEqualTo(onHand);
    assertThat(reserved(product)).isEqualTo(reserved);
  }

  private String state(String order) {
    return jdbc.queryForObject(
        "SELECT status FROM customer_orders WHERE id=?", String.class, order);
  }

  private void dueNow(String order) {
    jdbc.update("UPDATE customer_orders SET expires_at=created_at WHERE id=?", order);
  }

  private dev.lattante.commerceflow.identity.Actor ownerActor() {
    return new dev.lattante.commerceflow.identity.Actor(
        owner.id(),
        "customer@example.test",
        dev.lattante.commerceflow.identity.AppUser.Role.CUSTOMER);
  }

  @Test
  void approvedPaymentConsumesStockOnceAndPersistsOnlyKeyDigest() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    var paid =
        payment(owner.token(), order, "payment-key-01", "APPROVED")
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.status").value("PAID"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    stockIs(product, 8, 0);
    payment(owner.token(), order, "payment-key-01", "APPROVED")
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(content().json(paid));
    stockIs(product, 8, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT payment_key_hash FROM customer_orders", String.class))
        .matches("[0-9a-f]{64}")
        .isNotEqualTo("payment-key-01");
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status')) FROM outbox_events WHERE"
                    + " event_type='ORDER_PAID'",
                String.class))
        .isEqualTo("PAID");
    assertThat(
            jdbc.queryForObject(
                "SELECT JSON_CONTAINS_PATH(payload,'one','$.paymentKeyHash') FROM outbox_events"
                    + " WHERE event_type='ORDER_PAID'",
                Integer.class))
        .isZero();
  }

  @Test
  void changedPaymentDecisionOrKeyConflictsWithoutSideEffects() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    payment(owner.token(), order, "payment-key-01", "APPROVED").andExpect(status().isOk());
    payment(owner.token(), order, "payment-key-01", "DECLINED")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("idempotency_conflict"));
    payment(owner.token(), order, "different-key", "APPROVED").andExpect(status().isConflict());
    cancel(owner.token(), order)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("order_state_conflict"));
    stockIs(product, 8, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void declinedPaymentReleasesStockAndIsTerminal() throws Exception {
    String product = product("A", 10), order = newOrder(product, 3);
    payment(owner.token(), order, "declined-key", "DECLINED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAYMENT_DECLINED"));
    payment(owner.token(), order, "declined-key", "DECLINED")
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    cancel(owner.token(), order).andExpect(status().isConflict());
    stockIs(product, 10, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
    assertThat(state(order)).isEqualTo("PAYMENT_DECLINED");
  }

  @Test
  void cancellationIsIdempotentAndDoesNotPermitPaymentAfterwards() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    for (int i = 0; i < 3; i++)
      cancel(owner.token(), order)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("CANCELLED"));
    payment(owner.token(), order, "payment-key-01", "APPROVED").andExpect(status().isConflict());
    stockIs(product, 10, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void paymentKeysAreScopedToTheOrderAndCreationKeysDoNotCollide() throws Exception {
    String product = product("A", 10);
    String first =
        reply(owner.token(), "shared-key-01", items(product, 1)).body().get("id").asText();
    String second = newOrder(product, 2);
    payment(owner.token(), first, "shared-key-01", "APPROVED").andExpect(status().isOk());
    payment(owner.token(), second, "shared-key-01", "DECLINED").andExpect(status().isOk());
    stockIs(product, 9, 0);
    assertThat(count("outbox_events")).isEqualTo(4);
  }

  @Test
  void onlyOwnersCanMutateOrdersIncludingAgainstAdministratorTokens() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    Customer stranger = customer("stranger@example.test");
    for (String access : List.of(stranger.token(), admin)) {
      payment(access, order, "payment-key-01", "APPROVED").andExpect(status().isNotFound());
      cancel(access, order).andExpect(status().isNotFound());
    }
    stockIs(product, 10, 2);
    assertThat(state(order)).isEqualTo("RESERVED");
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void anonymousLifecycleOperationsAreRejected() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    mvc.perform(post("/api/orders/" + order + "/cancel")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/orders/" + order + "/simulated-payment")
                .header("Idempotency-Key", "payment-key-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}"))
        .andExpect(status().isUnauthorized());
    stockIs(product, 10, 2);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void malformedPaymentRequestsNeverChangeTheReservation() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    for (String payload : List.of("{}", "{\"decision\":null}", "{\"decision\":\"REAL_CHARGE\"}")) {
      mvc.perform(
              post("/api/orders/" + order + "/simulated-payment")
                  .header("Authorization", "Bearer " + owner.token())
                  .header("Idempotency-Key", "payment-key")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(payload))
          .andExpect(status().isBadRequest());
    }
    payment(owner.token(), order, "short", "APPROVED").andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/orders/" + order + "/simulated-payment")
                .header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}"))
        .andExpect(status().isBadRequest());
    stockIs(product, 10, 2);
    assertThat(count("outbox_events")).isEqualTo(1);
  }

  @Test
  void duePaymentCommitsExpiryInsteadOfRollingItBackWithAnException() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    dueNow(order);
    payment(owner.token(), order, "payment-key-01", "APPROVED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));
    payment(owner.token(), order, "payment-key-01", "APPROVED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));
    cancel(owner.token(), order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));
    assertThat(lifecycle.expire(order)).isFalse();
    stockIs(product, 10, 0);
    assertThat(state(order)).isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT payment_key_hash IS NULL FROM customer_orders", Boolean.class))
        .isTrue();
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void earlyExpiryIsANoOpAndDueCancellationExpiresRatherThanCancels() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    assertThat(lifecycle.expire(order)).isFalse();
    stockIs(product, 10, 2);
    dueNow(order);
    cancel(owner.token(), order)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));
    assertThat(lifecycle.expire(UUID.randomUUID().toString())).isFalse();
    stockIs(product, 10, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"APPROVED", "DECLINED", "CANCEL", "EXPIRE"})
  void lateOutboxFailureRollsBackLifecycleAndCanBeRetried(String operation) throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    if (operation.equals("EXPIRE")) dueNow(order);
    jdbc.execute(
        "CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox_events FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected test failure'");
    try {
      if (operation.equals("EXPIRE"))
        assertThatThrownBy(() -> lifecycle.expire(order)).isInstanceOf(RuntimeException.class);
      else if (operation.equals("CANCEL"))
        cancel(owner.token(), order).andExpect(status().isInternalServerError());
      else
        payment(owner.token(), order, "rollback-key", operation)
            .andExpect(status().isInternalServerError());
      assertThat(state(order)).isEqualTo("RESERVED");
      stockIs(product, 10, 2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT payment_key_hash IS NULL AND completed_at IS NULL FROM customer_orders",
                  Boolean.class))
          .isTrue();
      assertThat(count("outbox_events")).isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER reject_outbox");
    }
    if (operation.equals("EXPIRE")) assertThat(lifecycle.expire(order)).isTrue();
    else if (operation.equals("CANCEL")) cancel(owner.token(), order).andExpect(status().isOk());
    else payment(owner.token(), order, "rollback-key", operation).andExpect(status().isOk());
    stockIs(product, operation.equals("APPROVED") ? 8 : 10, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void simultaneousPaymentRetriesConsumeOnlyOnce() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    var results =
        concurrently(
            12,
            i -> {
              var r =
                  lifecycle.pay(
                      order,
                      ownerActor(),
                      "concurrent-pay-key",
                      dev.lattante.commerceflow.orders.PaymentDecision.APPROVED);
              return new Reply(200, json.valueToTree(r.order()), Boolean.toString(r.replayed()));
            });
    assertThat(results.stream().filter(r -> r.replayed().equals("false")).count()).isEqualTo(1);
    assertThat(results).allMatch(r -> r.body().get("status").asText().equals("PAID"));
    stockIs(product, 8, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void paymentVersusCancellationHasOneTerminalWinner() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    var results =
        concurrently(
            16,
            i -> {
              try {
                var r =
                    i % 2 == 0
                        ? lifecycle
                            .pay(
                                order,
                                ownerActor(),
                                "race-payment-key",
                                dev.lattante.commerceflow.orders.PaymentDecision.APPROVED)
                            .order()
                        : lifecycle.cancel(order, ownerActor());
                return new Reply(200, json.valueToTree(r), null);
              } catch (dev.lattante.commerceflow.orders.OrderStateConflict conflict) {
                return new Reply(409, null, null);
              }
            });
    assertThat(results).allMatch(r -> r.status() == 200 || r.status() == 409);
    assertThat(
            results.stream()
                .filter(r -> r.status() == 200)
                .map(r -> r.body().get("status").asText())
                .distinct()
                .count())
        .isEqualTo(1);
    String winner = state(order);
    assertThat(winner).isIn("PAID", "CANCELLED");
    stockIs(product, winner.equals("PAID") ? 8 : 10, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void duePaymentVersusMultipleExpiryWorkersReleasesOnlyOnce() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    dueNow(order);
    concurrently(
        16,
        i -> {
          if (i % 2 == 0)
            lifecycle.pay(
                order,
                ownerActor(),
                "expiry-race-key",
                dev.lattante.commerceflow.orders.PaymentDecision.APPROVED);
          else lifecycle.expire(order);
          return new Reply(200, null, null);
        });
    assertThat(state(order)).isEqualTo("EXPIRED");
    stockIs(product, 10, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void paidOrderReplaySurvivesItsOriginalDeadlineWithoutReleasingSoldStock() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    payment(owner.token(), order, "payment-key-01", "APPROVED").andExpect(status().isOk());
    org.mockito.Mockito.when(lifecycleClock.instant())
        .thenReturn(java.time.Instant.parse("2026-01-01T01:00:00Z"));
    assertThat(lifecycle.expire(order)).isFalse();
    assertThat(
            lifecycle
                .pay(
                    order,
                    ownerActor(),
                    "payment-key-01",
                    dev.lattante.commerceflow.orders.PaymentDecision.APPROVED)
                .replayed())
        .isTrue();
    stockIs(product, 8, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void databaseRejectsTerminalStatesWithoutMatchingPaymentMetadata() throws Exception {
    String product = product("A", 10), order = newOrder(product, 2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE customer_orders SET status='PAID',completed_at=created_at WHERE id=?",
                    order))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasRootCauseInstanceOf(java.sql.SQLException.class)
        .satisfies(
            error -> {
              var sql =
                  (java.sql.SQLException)
                      ((org.springframework.dao.DataAccessException) error).getMostSpecificCause();
              assertThat(sql.getErrorCode()).isEqualTo(3819);
              assertThat(sql.getMessage()).contains("ck_order_payment");
            });
    assertThat(state(order)).isEqualTo("RESERVED");
    stockIs(product, 10, 2);
  }

  @Test
  void openApiListsLifecycleOperationsWithBearerRequirements() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.paths['/api/orders/{id}/simulated-payment'].post.security[0].bearerAuth")
                .isArray())
        .andExpect(
            jsonPath("$.paths['/api/orders/{id}/cancel'].post.security[0].bearerAuth").isArray());
  }

  @Test
  void failureOnALaterInventoryLineRollsBackEarlierConsumption() throws Exception {
    var ids = new ArrayList<>(List.of(product("A", 10), product("B", 10)));
    ids.sort(String::compareTo);
    String order =
        reply(owner.token(), "multi-line-order", List.of(item(ids.get(1), 2), item(ids.get(0), 2)))
            .body()
            .get("id")
            .asText();
    // Deliberate test-only drift: still satisfies product CHECK, but violates the order
    // reservation.
    jdbc.update("UPDATE products SET reserved=0 WHERE id=?", ids.get(1));
    payment(owner.token(), order, "multi-line-payment", "APPROVED")
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("internal_error"));
    stockIs(ids.get(0), 10, 2);
    stockIs(ids.get(1), 10, 0);
    assertThat(state(order)).isEqualTo("RESERVED");
    assertThat(count("outbox_events")).isEqualTo(1);
    jdbc.update("UPDATE products SET reserved=2 WHERE id=?", ids.get(1));
    payment(owner.token(), order, "multi-line-payment", "APPROVED").andExpect(status().isOk());
    for (String id : ids) stockIs(id, 8, 0);
    assertThat(count("outbox_events")).isEqualTo(2);
  }

  @Test
  void distinctOrdersWithSharedProductsCompleteWithoutInconsistentLockOrdering() throws Exception {
    String a = product("A", 20), b = product("B", 20);
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 8; i++)
      ids.add(
          reply(
                  owner.token(),
                  "shared-order-" + i,
                  i % 2 == 0 ? List.of(item(a, 1), item(b, 1)) : List.of(item(b, 1), item(a, 1)))
              .body()
              .get("id")
              .asText());
    concurrently(
        8,
        i -> {
          if (i % 2 == 0)
            lifecycle.pay(
                ids.get(i),
                ownerActor(),
                "shared-payment-key",
                dev.lattante.commerceflow.orders.PaymentDecision.APPROVED);
          else lifecycle.cancel(ids.get(i), ownerActor());
          return new Reply(200, null, null);
        });
    stockIs(a, 16, 0);
    stockIs(b, 16, 0);
    assertThat(count("outbox_events")).isEqualTo(16);
  }

  @Test
  void expiryQuerySelectsOnlyDueReservationsInStableBoundedOrder() throws Exception {
    String product = product("A", 200);
    List<String> due = new ArrayList<>();
    // Populate through the real creation API to retain all domain invariants and FK data.
    for (int i = 0; i < 102; i++) {
      String id = newOrder(product, 1);
      due.add(id);
      dueNow(id);
    }
    String future = newOrder(product, 1);
    String cancelled = newOrder(product, 1);
    cancel(owner.token(), cancelled).andExpect(status().isOk());
    due.sort(String::compareTo); // identical deadlines in the controlled clock
    assertThat(lifecycle.dueCandidates()).containsExactlyElementsOf(due.subList(0, 100));
    assertThat(lifecycle.expire(due.getFirst())).isTrue();
    assertThat(lifecycle.dueCandidates())
        .hasSize(100)
        .doesNotContain(due.getFirst(), future, cancelled);
    stockIs(product, 200, 102); // 101 remaining due + one future; cancellation/expiry released
  }

  @Test
  void transportEnqueueFailureAlsoRollsBackTheBusinessOutboxAndReservation() throws Exception {
    String product = product("A", 10);
    jdbc.execute(
        "CREATE TRIGGER reject_dispatch BEFORE INSERT ON message_dispatch FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected transport failure'");
    try {
      submit(owner.token(), "transport-rollback-key", items(product, 2))
          .andExpect(status().isInternalServerError());
      nothingCommitted();
      stockIs(product, 10, 0);
      assertThat(count("message_dispatch")).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER reject_dispatch");
    }
    submit(owner.token(), "transport-rollback-key", items(product, 2))
        .andExpect(status().isCreated());
    assertThat(count("outbox_events")).isEqualTo(1);
    assertThat(count("message_dispatch")).isEqualTo(1);
  }

  private double transitions(String action) {
    return transitionMetrics.counter("commerceflow.order.transitions", "action", action).count();
  }

  @Test
  void creationAndPaymentReplayProduceOneAuditAndOneCommittedCounterPerTransition()
      throws Exception {
    String product = product("AUDIT", 10);
    double reservedBefore = transitions("order_reserved"), paidBefore = transitions("order_paid");
    var first = reply(owner.token(), "audit-create-key", items(product, 2));
    String order = first.body().get("id").asText();
    assertThat(reply(owner.token(), "audit-create-key", items(product, 2)).status()).isEqualTo(200);
    payment(owner.token(), order, "audit-payment-key", "APPROVED").andExpect(status().isOk());
    payment(owner.token(), order, "audit-payment-key", "APPROVED").andExpect(status().isOk());
    assertThat(count("order_operation_audit")).isEqualTo(2);
    assertThat(transitions("order_reserved")).isEqualTo(reservedBefore + 1);
    assertThat(transitions("order_paid")).isEqualTo(paidBefore + 1);
    mvc.perform(
            get("/api/orders/" + order + "/audit")
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sequence").value(1))
        .andExpect(jsonPath("$[1].sequence").value(2))
        .andExpect(jsonPath("$[1].actorId").value(owner.id()));
    var stranger = customer("audit-stranger@example.test");
    mvc.perform(
            get("/api/orders/" + order + "/audit")
                .header("Authorization", "Bearer " + stranger.token()))
        .andExpect(status().isNotFound());
  }

  @Test
  void auditInsertionFailureRollsBackAllOrderSideEffects() throws Exception {
    String product = product("AUDIT", 10);
    double before = transitions("order_reserved");
    jdbc.execute(
        "CREATE TRIGGER reject_audit BEFORE INSERT ON order_operation_audit FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected audit failure'");
    try {
      submit(owner.token(), "audit-rollback-key", items(product, 2))
          .andExpect(status().isInternalServerError());
      nothingCommitted();
      stockIs(product, 10, 0);
      assertThat(count("message_dispatch")).isZero();
      assertThat(count("order_operation_audit")).isZero();
      assertThat(transitions("order_reserved")).isEqualTo(before);
    } finally {
      jdbc.execute("DROP TRIGGER reject_audit");
    }
    submit(owner.token(), "audit-rollback-key", items(product, 2)).andExpect(status().isCreated());
    assertThat(count("order_operation_audit")).isEqualTo(1);
    assertThat(transitions("order_reserved")).isEqualTo(before + 1);
  }

  @Test
  void anOutboxFailureAfterAuditRegistrationDoesNotIncrementCommittedCounters() throws Exception {
    String product = product("AUDIT", 10);
    double before = transitions("order_reserved");
    jdbc.execute(
        "CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox_events FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected later failure'");
    try {
      submit(owner.token(), "metric-rollback-key", items(product, 1))
          .andExpect(status().isInternalServerError());
      assertThat(count("order_operation_audit")).isZero();
      assertThat(transitions("order_reserved")).isEqualTo(before);
    } finally {
      jdbc.execute("DROP TRIGGER reject_outbox");
    }
  }

  @Test
  void expiryAuditAttributesTheTransitionToTheSystemAndDoesNotDuplicateIt() throws Exception {
    String product = product("AUDIT", 10), order = newOrder(product, 2);
    dueNow(order);
    assertThat(lifecycle.expire(order)).isTrue();
    assertThat(lifecycle.expire(order)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_id IS NULL FROM order_operation_audit WHERE sequence_no=2",
                Boolean.class))
        .isTrue();
    assertThat(count("order_operation_audit")).isEqualTo(2);
    stockIs(product, 10, 0);
  }

  private String catalogPath(String product) {
    return "/api/admin/catalog/products/" + product;
  }

  private long productVersion(String product) {
    return jdbc.queryForObject("SELECT version FROM products WHERE id=?", Long.class, product);
  }

  private Map<String, Object> catalogEdit(String product, int onHand, boolean active) {
    return new HashMap<>(
        Map.of(
            "expectedVersion",
            productVersion(product),
            "name",
            "Revised product",
            "amount",
            "25.00",
            "currency",
            "USD",
            "onHand",
            onHand,
            "active",
            active,
            "reason",
            "Inventory review"));
  }

  private ResultActions changeProduct(String product, String token, Map<String, Object> input)
      throws Exception {
    return mvc.perform(
        put(catalogPath(product))
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(input)));
  }

  @Test
  void catalogAdministrationIsAdminOnlyIncludingHistoryAndInactiveDiscovery() throws Exception {
    String id = product("ADMIN-CATALOG", 10);
    for (String route :
        List.of("/api/admin/catalog/products", catalogPath(id), catalogPath(id) + "/audit")) {
      mvc.perform(get(route)).andExpect(status().isUnauthorized());
      mvc.perform(get(route).header("Authorization", "Bearer " + owner.token()))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            put(catalogPath(id))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(catalogEdit(id, 20, false))))
        .andExpect(status().isUnauthorized());
    changeProduct(id, owner.token(), catalogEdit(id, 20, false)).andExpect(status().isForbidden());
    stockIs(id, 10, 0);
    assertThat(count("product_change_audit")).isZero();
  }

  @Test
  void changesAreAtomicAuditedAndCatalogVersionAdvances() throws Exception {
    String id = product("ADMIN-CATALOG", 10);
    changeProduct(id, admin, catalogEdit(id, 20, true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state.version").value(1))
        .andExpect(jsonPath("$.state.onHand").value(20));
    mvc.perform(get(catalogPath(id) + "/audit").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].before.name").value("Snapshot ADMIN-CATALOG"))
        .andExpect(jsonPath("$.content[0].before.onHand").value(10))
        .andExpect(jsonPath("$.content[0].after.onHand").value(20))
        .andExpect(jsonPath("$.content[0].after.version").value(1))
        .andExpect(jsonPath("$.content[0].reason").value("Inventory review"));
    assertThat(jdbc.queryForObject("SELECT actor_id FROM product_change_audit", String.class))
        .isEqualTo(
            jdbc.queryForObject("SELECT id FROM app_users WHERE email=?", String.class, ADMIN));
    mvc.perform(get("/api/products/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Revised product"))
        .andExpect(jsonPath("$.amount").value(25.00));
  }

  @Test
  void disablingHidesPublicProductButCanBeDiscoveredAndReactivatedByAdmin() throws Exception {
    String id = product("DEACTIVATED", 10);
    changeProduct(id, admin, catalogEdit(id, 10, false)).andExpect(status().isOk());
    mvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound());
    mvc.perform(get("/api/products")).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/admin/catalog/products")
                .param("active", "false")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(id))
        .andExpect(jsonPath("$.totalElements").value(1));
    submit(owner.token(), "inactive-product-key", items(id, 1)).andExpect(status().isConflict());
    nothingCommitted();
    changeProduct(id, admin, catalogEdit(id, 10, true)).andExpect(status().isOk());
    submit(owner.token(), "inactive-product-key", items(id, 1)).andExpect(status().isCreated());
    assertThat(count("product_change_audit")).isEqualTo(2);
  }

  @Test
  void existingOrderSnapshotsAndPaymentsSurviveCatalogRevisionAndDeactivation() throws Exception {
    String id = product("SNAPSHOT-ADMIN", 10), order = newOrder(id, 2);
    var input = catalogEdit(id, 12, false);
    input.put("currency", "EUR");
    changeProduct(id, admin, input).andExpect(status().isOk());
    payment(owner.token(), order, "old-price-payment", "APPROVED")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(24.68))
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.items[0].name").value("Snapshot SNAPSHOT-ADMIN"));
    stockIs(id, 10, 0);
    assertThat(count("product_change_audit")).isEqualTo(1);
  }

  @Test
  void cancellingAnInactiveProductStillReleasesItsReservation() throws Exception {
    String id = product("CANCEL-INACTIVE", 10), order = newOrder(id, 2);
    changeProduct(id, admin, catalogEdit(id, 10, false)).andExpect(status().isOk());
    mvc.perform(
            post("/api/orders/" + order + "/cancel")
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isOk());
    stockIs(id, 10, 0);
  }

  @Test
  void reducingOnHandBelowReservationsIsAConflictWithNoPartialChangeOrAudit() throws Exception {
    String id = product("RESERVED-ADMIN", 10);
    newOrder(id, 4);
    long version = productVersion(id);
    changeProduct(id, admin, catalogEdit(id, 3, false))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("product_conflict"));
    stockIs(id, 10, 4);
    assertThat(productVersion(id)).isEqualTo(version);
    assertThat(count("product_change_audit")).isZero();
    mvc.perform(get("/api/products/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Snapshot RESERVED-ADMIN"));
  }

  @Test
  void staleEditsAndRepeatedPutDoNotApplyAgainOrDuplicateHistory() throws Exception {
    String id = product("VERSIONED", 10);
    var input = catalogEdit(id, 15, true);
    changeProduct(id, admin, input).andExpect(status().isOk());
    changeProduct(id, admin, input).andExpect(status().isConflict());
    var noOp = catalogEdit(id, 15, true);
    changeProduct(id, admin, noOp)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state.version").value(1));
    assertThat(count("product_change_audit")).isEqualTo(1);
    stockIs(id, 15, 0);
  }

  @Test
  void inventoryReservationAdvancesTheVersionAndInvalidatesAdministrativeSnapshot()
      throws Exception {
    String id = product("RESERVATION-VERSION", 10);
    var input = catalogEdit(id, 15, true);
    newOrder(id, 2);
    changeProduct(id, admin, input).andExpect(status().isConflict());
    stockIs(id, 10, 2);
    assertThat(count("product_change_audit")).isZero();
  }

  @Test
  void auditFailureRollsBackProductStateAndHibernateVersion() throws Exception {
    String id = product("AUDIT-PRODUCT", 10);
    jdbc.execute(
        "CREATE TRIGGER reject_product_audit BEFORE INSERT ON product_change_audit FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected product audit failure'");
    try {
      changeProduct(id, admin, catalogEdit(id, 20, false))
          .andExpect(status().isInternalServerError());
      stockIs(id, 10, 0);
      assertThat(productVersion(id)).isZero();
      assertThat(count("product_change_audit")).isZero();
      mvc.perform(get("/api/products/" + id))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Snapshot AUDIT-PRODUCT"));
    } finally {
      jdbc.execute("DROP TRIGGER reject_product_audit");
    }
    changeProduct(id, admin, catalogEdit(id, 20, false)).andExpect(status().isOk());
    assertThat(count("product_change_audit")).isEqualTo(1);
  }

  @Test
  void concurrentAdministratorsCannotOverwriteTheSameVersion() throws Exception {
    String id = product("RACE-ADMIN", 10);
    var first = catalogEdit(id, 20, true);
    var second = catalogEdit(id, 30, true);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                start.await();
                return changeProduct(id, admin, first).andReturn().getResponse().getStatus();
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return changeProduct(id, admin, second).andReturn().getResponse().getStatus();
              });
      start.countDown();
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(productVersion(id)).isEqualTo(1);
      assertThat(count("product_change_audit")).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void reservationAndDeactivationSerializeWithoutOversellingOrErasingReservations()
      throws Exception {
    String id = product("RACE-INVENTORY", 10);
    var input = catalogEdit(id, 1, false);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var edit =
          pool.submit(
              () -> {
                start.await();
                return changeProduct(id, admin, input).andReturn().getResponse().getStatus();
              });
      var order =
          pool.submit(
              () -> {
                start.await();
                return reply(owner.token(), "admin-reserve-race", items(id, 2)).status();
              });
      start.countDown();
      int edited = edit.get(20, TimeUnit.SECONDS), ordered = order.get(20, TimeUnit.SECONDS);
      if (edited == 200) {
        assertThat(ordered).isEqualTo(409);
        stockIs(id, 1, 0);
        nothingCommitted();
      } else {
        assertThat(edited).isEqualTo(409);
        assertThat(ordered).isEqualTo(201);
        stockIs(id, 10, 2);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void catalogAuditIsPaginatedAndOrderedByVersion() throws Exception {
    String id = product("PAGED-AUDIT", 10);
    changeProduct(id, admin, catalogEdit(id, 20, true)).andExpect(status().isOk());
    changeProduct(id, admin, catalogEdit(id, 30, true)).andExpect(status().isOk());
    mvc.perform(
            get(catalogPath(id) + "/audit")
                .param("size", "1")
                .param("page", "1")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].after.version").value(1));
    mvc.perform(
            get(catalogPath(id) + "/audit")
                .param("size", "101")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(catalogPath(UUID.randomUUID().toString()) + "/audit")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isNotFound());
  }

  @Test
  void administrationValidatesRequiredFieldsAndDoesNotPermitDirectReservationAssignment()
      throws Exception {
    String id = product("INPUT-ADMIN", 10);
    for (String field :
        List.of("expectedVersion", "name", "amount", "currency", "onHand", "active", "reason")) {
      var input = catalogEdit(id, 10, false);
      input.remove(field);
      changeProduct(id, admin, input).andExpect(status().isBadRequest());
    }
    var input = catalogEdit(id, 10, true);
    input.put("reserved", 9);
    input.put("sku", "FORGED");
    changeProduct(id, admin, input)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sku").value("INPUT-ADMIN"))
        .andExpect(jsonPath("$.state.reserved").value(0));
    assertThat(count("product_change_audit")).isEqualTo(1);
  }

  @Test
  void multiProductOrderRollsBackEarlierReservationsWhenAnInactiveProductIsEncountered()
      throws Exception {
    String a = product("ACTIVE-BASKET", 10), b = product("INACTIVE-BASKET", 10);
    var ids = new ArrayList<>(List.of(a, b));
    Collections.sort(ids);
    changeProduct(ids.get(1), admin, catalogEdit(ids.get(1), 10, false)).andExpect(status().isOk());
    submit(owner.token(), "inactive-basket-key", List.of(item(ids.get(0), 2), item(ids.get(1), 2)))
        .andExpect(status().isConflict());
    stockIs(a, 10, 0);
    stockIs(b, 10, 0);
    nothingCommitted();
    assertThat(count("message_dispatch")).isZero();
    assertThat(count("order_operation_audit")).isZero();
    assertThat(count("product_change_audit")).isEqualTo(1);
  }

  @Test
  void paymentAndStockReconciliationCannotLoseConsumedUnits() throws Exception {
    String id = product("RACE-PAYMENT", 10), order = newOrder(id, 2);
    var input = catalogEdit(id, 20, true);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var edit =
          pool.submit(
              () -> {
                start.await();
                return changeProduct(id, admin, input).andReturn().getResponse().getStatus();
              });
      var paid =
          pool.submit(
              () -> {
                start.await();
                return payment(owner.token(), order, "admin-payment-race", "APPROVED")
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      start.countDown();
      int edited = edit.get(20, TimeUnit.SECONDS);
      assertThat(paid.get(20, TimeUnit.SECONDS)).isEqualTo(200);
      if (edited == 200) {
        stockIs(id, 18, 0);
        assertThat(count("product_change_audit")).isEqualTo(1);
      } else {
        assertThat(edited).isEqualTo(409);
        stockIs(id, 8, 0);
        assertThat(count("product_change_audit")).isZero();
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
