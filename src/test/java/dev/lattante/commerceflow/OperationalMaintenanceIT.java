package dev.lattante.commerceflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lattante.commerceflow.catalog.*;
import dev.lattante.commerceflow.identity.*;
import dev.lattante.commerceflow.inventory.InventoryReservation.Demand;
import dev.lattante.commerceflow.messaging.*;
import dev.lattante.commerceflow.orders.*;
import dev.lattante.commerceflow.shared.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(
    properties = {
      "CF_BOOTSTRAP_ADMIN_EMAIL=",
      "CF_BOOTSTRAP_ADMIN_PASSWORD=",
      "commerceflow.reservations.scheduling-enabled=false",
      "commerceflow.identity.cleanup.enabled=true",
      "commerceflow.identity.cleanup.batch-size=3",
      "commerceflow.identity.cleanup.interval-ms=3600000",
      "commerceflow.identity.cleanup.initial-delay-ms=3600000"
    })
@AutoConfigureObservability(tracing = false)
@AutoConfigureMockMvc
@Testcontainers
class OperationalMaintenanceIT {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00.123456Z");
  private static final String ADMIN = "admin@example.test", PASS = "local-maintenance-test-fixture";

  @Container
  static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.4.11")
          .withCommand("--log-bin-trust-function-creators=1")
          .withDatabaseName("maintenance_test")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired IdentityService identity;
  @Autowired ExpiredTokenCleanup cleanup;
  @Autowired TokenCleanupWorker worker;
  @Autowired MeterRegistry metrics;
  @Autowired PlatformTransactionManager manager;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired CatalogService catalog;
  @Autowired ProductAdministration products;
  @Autowired UserAdministration users;
  @Autowired OrderService orders;
  @Autowired OrderInbox inbox;
  @Autowired MessageOperations messaging;
  @MockitoBean Clock clock;
  private String adminToken, customerToken, customerId;
  private Actor admin, customer;

  @BeforeEach
  void clean() {
    when(clock.instant()).thenReturn(NOW);
    jdbc.execute("DROP TRIGGER IF EXISTS reject_expired_delete");
    for (String table :
        List.of(
            "user_admin_audit",
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
    adminToken = identity.login(ADMIN, PASS).accessToken();
    admin = identity.authenticate(adminToken).orElseThrow();
    customerId = identity.register("customer@example.test", PASS).id();
    customerToken = identity.login("customer@example.test", PASS).accessToken();
    customer = identity.authenticate(customerToken).orElseThrow();
  }

  private String digest(int n) {
    return String.format("%064x", n);
  }

  private void token(int n, Instant expiry) {
    jdbc.update(
        "INSERT INTO access_tokens(digest,user_id,expires_at) VALUES(?,?,?)",
        digest(n),
        customerId,
        Timestamp.from(expiry));
  }

  private boolean tokenExists(int n) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM access_tokens WHERE digest=?", Long.class, digest(n))
        == 1;
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private double counter(String name, String... tags) {
    Counter value = metrics.find(name).tags(tags).counter();
    return value == null ? 0 : value.count();
  }

  private double committed() {
    return counter("commerceflow.identity.cleanup.runs", "outcome", "committed");
  }

  private double deleted() {
    return counter("commerceflow.identity.cleanup.deleted");
  }

  private TransactionTemplate transaction() {
    var tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return tx;
  }

  private String deliveryId(int n) {
    return new UUID(0, n).toString();
  }

  private void delivery(int n, String kind, String state, String payload) {
    jdbc.update(
        """
        INSERT INTO message_dispatch(id,kind,source_id,topic,payload,state,attempts,retry_cycles,
          next_attempt_at,lease_token,lease_until,created_at,sent_at)
        VALUES(?,?,?,'metadata-fixture',?,?,5,0,?,?,?,?,?)
        """,
        deliveryId(n),
        kind,
        deliveryId(n),
        payload,
        state,
        Timestamp.from(NOW),
        state.equals("IN_FLIGHT") ? UUID.randomUUID().toString() : null,
        state.equals("IN_FLIGHT") ? Timestamp.from(NOW.plusSeconds(30)) : null,
        Timestamp.from(NOW),
        state.equals("SENT") ? Timestamp.from(NOW) : null);
  }

  private void deadLetter(int n, boolean replayable, int replays, String payload) {
    jdbc.update(
        """
        INSERT INTO message_dead_letters(id,source_topic,source_partition,source_offset,payload,
          payload_hash,replayable,reason_code,replay_count,created_at)
        VALUES(?,'metadata-fixture',0,?,?,?,?,'INVALID_PROTOCOL',?,?)
        """,
        digest(n),
        n,
        payload,
        "a".repeat(64),
        replayable,
        replays,
        Timestamp.from(NOW));
  }

  @Test
  void onlyExpiredTokensIncludingTheExactBoundaryAreDeleted() {
    token(1, NOW.minusSeconds(1));
    token(2, NOW);
    token(3, NOW.plusNanos(1000));
    assertThat(cleanup.clean(100)).isEqualTo(2);
    assertThat(tokenExists(1)).isFalse();
    assertThat(tokenExists(2)).isFalse();
    assertThat(tokenExists(3)).isTrue();
    assertThat(identity.authenticate(adminToken)).isPresent();
    assertThat(identity.authenticate(customerToken)).isPresent();
    assertThat(count("app_users")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject("SELECT version FROM app_users WHERE id=?", Long.class, customerId))
        .isZero();
  }

  @Test
  void batchesAreBoundedOrderedAndRepeatedScansDoNotDuplicateDeletionMetrics() {
    for (int n = 1; n <= 7; n++) token(n, NOW.minusSeconds(1));
    double beforeDeleted = deleted(), beforeRuns = committed();
    assertThat(cleanup.clean(3)).isEqualTo(3);
    assertThat(tokenExists(1)).isFalse();
    assertThat(tokenExists(3)).isFalse();
    assertThat(tokenExists(4)).isTrue();
    assertThat(cleanup.clean(3)).isEqualTo(3);
    assertThat(cleanup.clean(3)).isEqualTo(1);
    assertThat(cleanup.clean(3)).isZero();
    assertThat(deleted()).isEqualTo(beforeDeleted + 7);
    assertThat(committed()).isEqualTo(beforeRuns + 4);
    assertThat(count("access_tokens")).isEqualTo(2);
  }

  @Test
  void outerRollbackRestoresTokensAndDoesNotPublishCommittedMetrics() {
    token(1, NOW);
    token(2, NOW);
    double beforeDeleted = deleted(), beforeRuns = committed();
    assertThatThrownBy(
            () ->
                transaction()
                    .execute(
                        status -> {
                          assertThat(cleanup.clean(3)).isEqualTo(2);
                          throw new IllegalStateException("Synthetic later transaction failure");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(tokenExists(1)).isTrue();
    assertThat(tokenExists(2)).isTrue();
    assertThat(deleted()).isEqualTo(beforeDeleted);
    assertThat(committed()).isEqualTo(beforeRuns);
  }

  @Test
  void deleteFailureRollsBackEarlierRowsAndWorkerRecoversOnTheNextTick() {
    token(1, NOW);
    token(2, NOW);
    double beforeDeleted = deleted(), beforeRuns = committed();
    double beforeFailed = counter("commerceflow.identity.cleanup.runs", "outcome", "failed");
    jdbc.execute(
        "CREATE TRIGGER reject_expired_delete BEFORE DELETE ON access_tokens FOR EACH ROW "
            + "BEGIN IF OLD.digest='"
            + digest(2)
            + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic cleanup failure'; END IF;"
            + " END");
    try {
      worker.tick();
      assertThat(tokenExists(1)).isTrue();
      assertThat(tokenExists(2)).isTrue();
      assertThat(deleted()).isEqualTo(beforeDeleted);
      assertThat(committed()).isEqualTo(beforeRuns);
      assertThat(counter("commerceflow.identity.cleanup.runs", "outcome", "failed"))
          .isEqualTo(beforeFailed + 1);
    } finally {
      jdbc.execute("DROP TRIGGER reject_expired_delete");
    }
    worker.tick();
    assertThat(tokenExists(1)).isFalse();
    assertThat(tokenExists(2)).isFalse();
    assertThat(deleted()).isEqualTo(beforeDeleted + 2);
    assertThat(committed()).isEqualTo(beforeRuns + 1);
  }

  @Test
  void concurrentWorkersClaimDisjointExpiredRowsWithoutDoubleCounting() throws Exception {
    for (int n = 1; n <= 12; n++) token(n, NOW);
    double before = deleted();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> tasks = new ArrayList<>();
      for (int n = 0; n < 4; n++)
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  return cleanup.clean(3);
                }));
      start.countDown();
      int total = 0;
      for (var task : tasks) total += task.get(20, TimeUnit.SECONDS);
      assertThat(total).isEqualTo(12);
      assertThat(deleted()).isEqualTo(before + 12);
      assertThat(count("access_tokens")).isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void lockedExpiredRowsAreSkippedAndRemainEligibleForTheNextScan() throws Exception {
    for (int n = 1; n <= 5; n++) token(n, NOW);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
    try {
      var holder =
          pool.submit(
              () ->
                  transaction()
                      .execute(
                          status -> {
                            jdbc.queryForList(
                                "SELECT digest FROM access_tokens WHERE digest=? FOR UPDATE",
                                String.class,
                                digest(1));
                            locked.countDown();
                            try {
                              if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("Fixture lock timed out");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                            return true;
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var scan = pool.submit(() -> cleanup.clean(5));
      assertThat(scan.get(3, TimeUnit.SECONDS)).isEqualTo(4);
      assertThat(tokenExists(1)).isTrue();
      release.countDown();
      assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(cleanup.clean(5)).isEqualTo(1);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void cleanupDoesNotChangeBusinessStateAuditsOrReplayGuarantees() {
    var product =
        catalog.create(
            "PRESERVE", "Preserved product", new BigDecimal("12.34"), Money.Currency.USD, 10);
    var submission = new OrderSubmission(List.of(new Demand(UUID.fromString(product.id()), 2)));
    var created = orders.create(customer, "maintenance-preserve-key", submission);
    String order = created.order().id();
    String wire =
        jdbc.queryForObject(
            "SELECT payload FROM message_dispatch WHERE aggregate_id=?", String.class, order);
    assertThat(inbox.apply(order, wire)).isTrue();
    long version =
        jdbc.queryForObject("SELECT version FROM products WHERE id=?", Long.class, product.id());
    products.update(
        product.id(),
        admin,
        new ProductEdit(
            version,
            "Reviewed product",
            new BigDecimal("12.34"),
            Money.Currency.USD,
            10,
            true,
            "Preservation fixture"));
    var auditTarget = identity.register("audit-target@example.test", PASS);
    users.update(
        auditTarget.id(),
        admin,
        new UserStateChange(0, AppUser.Role.CUSTOMER, false, "Preservation fixture"));
    delivery(100, "REPLAY", "FAILED", "opaque fixture");
    messaging.retry(deliveryId(100), admin, "maintenance-retry-fixture");
    deadLetter(100, false, 0, "opaque fixture");
    List<String> protectedTables =
        List.of(
            "app_users",
            "products",
            "customer_orders",
            "order_lines",
            "idempotency_requests",
            "outbox_events",
            "message_dispatch",
            "message_inbox",
            "order_projections",
            "message_dead_letters",
            "message_operator_requests",
            "order_operation_audit",
            "product_change_audit",
            "user_admin_audit");
    Map<String, Long> before = new HashMap<>();
    for (String table : protectedTables) {
      before.put(table, count(table));
      assertThat(count(table)).as(table).isPositive();
    }
    for (int n = 1; n <= 5; n++) token(n, NOW);
    assertThat(cleanup.clean(10)).isEqualTo(5);
    for (String table : protectedTables)
      assertThat(count(table)).as(table).isEqualTo(before.get(table));
    assertThat(orders.create(customer, "maintenance-preserve-key", submission).replayed()).isTrue();
    assertThat(inbox.apply(order, wire)).isFalse();
    for (String table : protectedTables)
      assertThat(count(table)).as(table).isEqualTo(before.get(table));
    assertThat(
            jdbc.queryForObject(
                "SELECT reserved FROM products WHERE id=?", Integer.class, product.id()))
        .isEqualTo(2);
  }

  @Test
  void snapshotIsAdminOnlyAndContainsOnlyAggregateMetadata() throws Exception {
    token(1, NOW.minusSeconds(7));
    String route = "/api/admin/operations/maintenance";
    mvc.perform(get(route)).andExpect(status().isUnauthorized());
    mvc.perform(get(route).header("Authorization", "Bearer " + customerToken))
        .andExpect(status().isForbidden());
    var response =
        mvc.perform(get(route).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cleanupEnabled").value(true))
            .andExpect(jsonPath("$.batchSize").value(3))
            .andExpect(jsonPath("$.expiredTokens").value(1))
            .andExpect(jsonPath("$.oldestExpiredAt").value(NOW.minusSeconds(7).toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain("digest", "password", "accessToken", customerId);
    assertThat(tokenExists(1)).isTrue();
    mvc.perform(
            post(route)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cutoff\":\"2100-01-01T00:00:00Z\"}"))
        .andExpect(status().isMethodNotAllowed());
    assertThat(tokenExists(1)).isTrue();
  }

  @Test
  void backlogGaugeReadsRealExpiredCountAndCleanupCommit() {
    token(1, NOW);
    token(2, NOW.plusSeconds(1));
    assertThat(metrics.get("commerceflow.identity.tokens.expired").gauge().value()).isEqualTo(1);
    cleanup.clean(3);
    assertThat(metrics.get("commerceflow.identity.tokens.expired").gauge().value()).isZero();
  }

  @Test
  void operatorPagesAreAdminOnlyAndValidateFiltersAndBounds() throws Exception {
    for (String route :
        List.of("/api/admin/messaging/deliveries", "/api/admin/messaging/dead-letters/page")) {
      mvc.perform(get(route)).andExpect(status().isUnauthorized());
      mvc.perform(get(route).header("Authorization", "Bearer " + customerToken))
          .andExpect(status().isForbidden());
      mvc.perform(get(route).param("size", "101").header("Authorization", "Bearer " + adminToken))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            get("/api/admin/messaging/deliveries")
                .param("state", "UNKNOWN")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/admin/messaging/deliveries")
                .param("kind", "UNKNOWN")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deliveryPagesFilterAndUseDeterministicOrderingWithoutExposingOpaquePayloads()
      throws Exception {
    String payload = "private-metadata-fixture:" + "x".repeat(1000000);
    delivery(1, "REPLAY", "FAILED", payload);
    delivery(2, "DLQ", "FAILED", payload);
    delivery(3, "REPLAY", "SENT", payload);
    var response =
        mvc.perform(
                get("/api/admin/messaging/deliveries")
                    .param("size", "1")
                    .param("page", "1")
                    .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].id").value(deliveryId(2)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response)
        .doesNotContain("private-metadata-fixture", "payload", "messageKey")
        .hasSizeLessThan(2000);
    mvc.perform(
            get("/api/admin/messaging/deliveries")
                .param("kind", "REPLAY")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            get("/api/admin/messaging/deliveries")
                .param("state", "SENT")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].sentAt").value(NOW.toString()));
    mvc.perform(
            get("/api/admin/messaging/failures").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void deadLetterPagesExposeReasonsAndBudgetsWithoutReadingOrReturningPayloads() throws Exception {
    String payload = "private-dead-letter-fixture:" + "x".repeat(1000000);
    deadLetter(1, true, 0, payload);
    deadLetter(2, true, 3, payload);
    deadLetter(3, false, 0, payload);
    var response =
        mvc.perform(
                get("/api/admin/messaging/dead-letters/page")
                    .param("size", "1")
                    .param("page", "1")
                    .param("replayable", "true")
                    .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].id").value(digest(2)))
            .andExpect(jsonPath("$.content[0].reasonCode").value("INVALID_PROTOCOL"))
            .andExpect(jsonPath("$.content[0].replayCount").value(3))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response)
        .doesNotContain("private-dead-letter-fixture", "payload", "payloadHash")
        .hasSizeLessThan(2000);
    mvc.perform(
            get("/api/admin/messaging/dead-letters/page")
                .param("replayable", "false")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(digest(3)));
    assertThat(count("message_dead_letters")).isEqualTo(3);
  }

  @Test
  void openApiIncludesReadOnlyMaintenanceAndPagedOperatorViews() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.paths['/api/admin/operations/maintenance'].get.security[0].bearerAuth")
                .isArray())
        .andExpect(
            jsonPath("$.paths['/api/admin/messaging/deliveries'].get.security[0].bearerAuth")
                .isArray());
  }

  @Test
  void authenticationRejectsExpiredCredentialsEvenBeforePhysicalCleanup() {
    jdbc.update(
        "UPDATE access_tokens SET expires_at=? WHERE user_id=?", Timestamp.from(NOW), customerId);
    assertThat(identity.authenticate(customerToken)).isEmpty();
    assertThat(identity.authenticate(adminToken)).isPresent();
    assertThat(cleanup.clean(3)).isEqualTo(1);
    assertThat(identity.authenticate(customerToken)).isEmpty();
    assertThat(identity.authenticate(adminToken)).isPresent();
  }
}
