package dev.lattante.commerceflow.operability;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lattante.commerceflow.catalog.CatalogService;
import dev.lattante.commerceflow.identity.*;
import dev.lattante.commerceflow.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(
    properties = {
      "CF_BOOTSTRAP_ADMIN_EMAIL=",
      "CF_BOOTSTRAP_ADMIN_PASSWORD=",
      "commerceflow.rate-limit.enabled=true",
      "commerceflow.rate-limit.login-limit=3",
      "commerceflow.rate-limit.registration-limit=2",
      "commerceflow.rate-limit.write-limit=7",
      "commerceflow.messaging.enabled=false",
      "commerceflow.reservations.scheduling-enabled=false",
      "spring.data.redis.timeout=200ms",
      "spring.data.redis.connect-timeout=200ms"
    })
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@Testcontainers
class RedisGuardIT {
  private static final String PASS = "rate-limit-test-fixture",
      REDIS_PASS = "redis-integration-test-fixture";

  @Container
  static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.4.11")
          .withDatabaseName("guard_test")
          .withUsername("test")
          .withPassword("test");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:8.2.11")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", REDIS_PASS);

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    r.add("spring.data.redis.password", () -> REDIS_PASS);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired RedisRateLimiter limiter;
  @Autowired RateLimitSettings settings;
  @Autowired MeterRegistry metrics;
  @Autowired IdentityService identity;
  @Autowired CatalogService catalog;
  String ownerToken, adminToken, ownerId, product;

  @BeforeEach
  void clean() {
    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.serverCommands().flushDb();
    }
    for (String table :
        List.of(
            "order_operation_audit",
            "message_operator_requests",
            "message_dispatch",
            "message_dead_letters",
            "message_inbox",
            "order_projections",
            "outbox_events",
            "idempotency_requests",
            "order_lines",
            "customer_orders",
            "access_tokens",
            "products",
            "app_users")) jdbc.update("DELETE FROM " + table);
    var owner = identity.register("owner@example.test", PASS);
    ownerId = owner.id();
    ownerToken = identity.login(owner.email(), PASS).accessToken();
    identity.bootstrap("admin@example.test", PASS);
    adminToken = identity.login("admin@example.test", PASS).accessToken();
    product =
        catalog
            .create(
                "GUARD-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                "Guard fixture",
                new BigDecimal("12.34"),
                Money.Currency.USD,
                20)
            .id();
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private String body(Object value) throws Exception {
    return json.writeValueAsString(value);
  }

  private ResultActions createOrder(String token, String key) throws Exception {
    return mvc.perform(
        post("/api/orders")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(Map.of("items", List.of(Map.of("productId", product, "quantity", 1))))));
  }

  private void eventually(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < until) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(50);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  @Test
  void loginAllowanceIgnoresForgedForwardingHeadersAndDoesNotEnumerateAccounts() throws Exception {
    for (int i = 0; i < 3; i++)
      mvc.perform(
              post("/api/auth/token")
                  .header("X-Forwarded-For", "198.51.100." + i)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      body(Map.of("email", "missing" + i + "@example.test", "password", PASS))))
          .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/token")
                .header("X-Forwarded-For", "203.0.113.99")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", "owner@example.test", "password", PASS))))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("rate_limited"));
    assertThat(count("app_users")).isEqualTo(2);
    assertThat(redis.keys("cf:rate:*")).allMatch(k -> k.matches("cf:rate:login:[0-9a-f]{64}"));
  }

  @Test
  void registrationBudgetPreventsExtraAccountsAndIsSeparateFromLogin() throws Exception {
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/auth/register")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(Map.of("email", "new" + i + "@example.test", "password", PASS))))
          .andExpect(status().isCreated());
    mvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", "blocked@example.test", "password", PASS))))
        .andExpect(status().isTooManyRequests());
    assertThat(count("app_users")).isEqualTo(4);
    mvc.perform(
            post("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", "owner@example.test", "password", PASS))))
        .andExpect(status().isOk());
  }

  @Test
  void authenticatedWritesShareActorBudgetAndLogoutRemainsAvailable() throws Exception {
    for (int i = 0; i < 7; i++)
      createOrder(ownerToken, "actor-order-" + i).andExpect(status().isCreated());
    createOrder(ownerToken, "actor-order-blocked").andExpect(status().isTooManyRequests());
    assertThat(count("customer_orders")).isEqualTo(7);
    assertThat(count("order_operation_audit")).isEqualTo(7);
    assertThat(jdbc.queryForObject("SELECT reserved FROM products", Integer.class)).isEqualTo(7);
    var other = identity.register("other@example.test", PASS);
    createOrder(identity.login(other.email(), PASS).accessToken(), "other-actor-order")
        .andExpect(status().isCreated());
    mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void forbiddenAndAnonymousWritesAreRejectedBeforeConsumingRedisAllowance() throws Exception {
    for (int i = 0; i < 10; i++)
      mvc.perform(
              post("/api/admin/messaging/dead-letters/" + "a".repeat(64) + "/replay")
                  .header("Authorization", "Bearer " + ownerToken)
                  .header("Idempotency-Key", "forbidden-key"))
          .andExpect(status().isForbidden());
    mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    assertThat(redis.hasKey(RedisRateLimiter.key(RateLimitSettings.Policy.WRITE, ownerId)))
        .isFalse();
    createOrder(ownerToken, "first-authorized-order").andExpect(status().isCreated());
  }

  @Test
  void concurrentLimiterInstancesCannotExceedTheSharedRedisBudget() throws Exception {
    var second = new RedisRateLimiter(redis, settings, metrics);
    String identity = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(24)) {
      var start = new CountDownLatch(1);
      List<Future<RedisRateLimiter.Decision>> futures = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        final int n = i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return (n % 2 == 0 ? limiter : second)
                      .acquire(RateLimitSettings.Policy.WRITE, identity);
                }));
      }
      start.countDown();
      int allowed = 0;
      for (var result : futures) if (result.get(10, TimeUnit.SECONDS).allowed()) allowed++;
      assertThat(allowed).isEqualTo(7);
    }
    assertThat(
            redis.opsForValue().get(RedisRateLimiter.key(RateLimitSettings.Policy.WRITE, identity)))
        .isEqualTo("7");
  }

  @Test
  void actualExpiryRestoresAllowanceWithoutApplicationClockDependence() throws Exception {
    var shortWindow = new RedisRateLimiter(redis, new RateLimitSettings(1, 2, 2, 2), metrics);
    String identity = UUID.randomUUID().toString();
    assertThat(shortWindow.acquire(RateLimitSettings.Policy.WRITE, identity).allowed()).isTrue();
    assertThat(shortWindow.acquire(RateLimitSettings.Policy.WRITE, identity).allowed()).isTrue();
    assertThat(shortWindow.acquire(RateLimitSettings.Policy.WRITE, identity).allowed()).isFalse();
    assertThat(
            redis.getExpire(
                RedisRateLimiter.key(RateLimitSettings.Policy.WRITE, identity),
                TimeUnit.MILLISECONDS))
        .isBetween(1L, 1000L);
    eventually(() -> shortWindow.acquire(RateLimitSettings.Policy.WRITE, identity).allowed());
  }

  @Test
  void redisPauseFailsClosedWithoutBusinessWritesAndRecoversWithSameKey() throws Exception {
    long before = count("customer_orders");
    REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    try {
      createOrder(ownerToken, "outage-retry-key")
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("rate_limit_unavailable"))
          .andExpect(header().string("Retry-After", "1"));
      assertThat(count("customer_orders")).isEqualTo(before);
      assertThat(count("idempotency_requests")).isZero();
      assertThat(count("order_operation_audit")).isZero();
      assertThat(jdbc.queryForObject("SELECT reserved FROM products", Integer.class)).isZero();
      mvc.perform(get("/api/products")).andExpect(status().isOk());
      mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
      mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + adminToken))
          .andExpect(status().isOk());
      mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + adminToken))
          .andExpect(status().isNoContent());
    } finally {
      REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
    eventually(limiter::available);
    createOrder(ownerToken, "outage-retry-key").andExpect(status().isCreated());
    assertThat(count("customer_orders")).isEqualTo(before + 1);
    mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
  }

  @Test
  void ownerAuditAndMetricsAreProtectedAndDoNotExposeCredentials() throws Exception {
    String response =
        createOrder(ownerToken, "observability-order")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(response).get("id").asText();
    mvc.perform(get("/api/orders/" + id + "/audit").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].action").value("ORDER_RESERVED"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(ownerToken))));
    mvc.perform(get("/api/admin/operations/audit").header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/operations/audit").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString("commerceflow_order_transitions_total")))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(ownerId))));
  }

  @Test
  void anExhaustedCounterWithoutATtlIsRepairedWithoutGrantingAnExtraRequest() {
    String identity = UUID.randomUUID().toString();
    String key = RedisRateLimiter.key(RateLimitSettings.Policy.WRITE, identity);
    redis.opsForValue().set(key, "7");
    assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isEqualTo(-1);
    assertThat(limiter.acquire(RateLimitSettings.Policy.WRITE, identity).allowed()).isFalse();
    assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 60000L);
    assertThat(redis.opsForValue().get(key)).isEqualTo("7");
  }
}
