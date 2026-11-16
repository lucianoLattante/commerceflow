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

@SpringBootTest(properties = {"CF_BOOTSTRAP_ADMIN_EMAIL=", "CF_BOOTSTRAP_ADMIN_PASSWORD="})
@AutoConfigureMockMvc
@Testcontainers
class OrderFlowIT {
  private static final String ADMIN = "admin@example.test", PASS = "local-only-test-fixture";

  @Container
  static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.4.11")
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
  private String admin;
  private Customer owner;

  private record Customer(String id, String token) {}

  private record Reply(int status, JsonNode body, String replayed) {}

  @BeforeEach
  void clean() throws Exception {
    jdbc.execute("DROP TRIGGER IF EXISTS reject_outbox");
    for (String table :
        List.of(
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
}
