package dev.lattante.commerceflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.lattante.commerceflow.identity.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
@AutoConfigureMockMvc
@Import(UserAdministrationIT.PasswordConfiguration.class)
@Testcontainers
class UserAdministrationIT {
  private static final String ADMIN = "admin@example.test", PASS = "local-only-user-admin-fixture";

  @Container
  static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.4.11")
          .withCommand("--log-bin-trust-function-creators=1")
          .withDatabaseName("user_administration_test")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", MYSQL::getJdbcUrl);
    r.add("spring.datasource.username", MYSQL::getUsername);
    r.add("spring.datasource.password", MYSQL::getPassword);
  }

  // Test-only latch proves issuance holds the real user row while password verification runs.
  static class BlockingPasswords implements PasswordEncoder {
    private final PasswordEncoder delegate = new BCryptPasswordEncoder(12);
    final AtomicBoolean block = new AtomicBoolean();
    volatile CountDownLatch entered = new CountDownLatch(0), proceed = new CountDownLatch(0);

    void arm() {
      entered = new CountDownLatch(1);
      proceed = new CountDownLatch(1);
      block.set(true);
    }

    void release() {
      block.set(false);
      proceed.countDown();
    }

    public String encode(CharSequence raw) {
      return delegate.encode(raw);
    }

    public boolean matches(CharSequence raw, String encoded) {
      if (block.compareAndSet(true, false)) {
        entered.countDown();
        try {
          if (!proceed.await(15, TimeUnit.SECONDS))
            throw new IllegalStateException("Test password latch timed out");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return delegate.matches(raw, encoded);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class PasswordConfiguration {
    @Bean
    @Primary
    BlockingPasswords blockingPasswords() {
      return new BlockingPasswords();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired IdentityService identity;
  @Autowired UserAdministration administration;
  @Autowired BlockingPasswords passwords;
  private String admin, adminId;
  private User customer;

  private record User(String id, String email, String token) {}

  @BeforeEach
  void clean() throws Exception {
    passwords.release();
    jdbc.execute("DROP TRIGGER IF EXISTS reject_user_audit");
    for (String table :
        List.of(
            "user_admin_audit", "product_change_audit", "access_tokens", "products", "app_users"))
      jdbc.update("DELETE FROM " + table);
    identity.bootstrap(ADMIN, PASS);
    admin = login(ADMIN);
    adminId = identity.authenticate(admin).orElseThrow().id();
    customer = customer("customer@example.test");
  }

  @AfterEach
  void releasePasswordLatch() {
    passwords.release();
  }

  private String body(Object value) throws Exception {
    return json.writeValueAsString(value);
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

  private User customer(String email) throws Exception {
    var registered =
        json.readTree(
            mvc.perform(
                    post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", PASS))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return new User(registered.get("id").asText(), email, login(email));
  }

  private User secondAdmin() throws Exception {
    String email = "second-admin@example.test";
    identity.bootstrap(email, PASS);
    String token = login(email);
    return new User(identity.authenticate(token).orElseThrow().id(), email, token);
  }

  private String path(String id) {
    return "/api/admin/users/" + id;
  }

  private long version(String id) {
    return jdbc.queryForObject("SELECT version FROM app_users WHERE id=?", Long.class, id);
  }

  private Map<String, Object> change(String id, String role, boolean active) {
    return new HashMap<>(
        Map.of(
            "expectedVersion",
            version(id),
            "role",
            role,
            "active",
            active,
            "reason",
            "Access review"));
  }

  private ResultActions update(String id, String token, Map<String, Object> input)
      throws Exception {
    return mvc.perform(
        put(path(id))
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(input)));
  }

  private ResultActions me(String token) throws Exception {
    return mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token));
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private long tokens(String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM access_tokens WHERE user_id=?", Long.class, id);
  }

  private long activeAdmins() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM app_users WHERE active=true AND role='ADMIN'", Long.class);
  }

  @Test
  void allAdministrationRoutesRequireCurrentAdministratorCredentials() throws Exception {
    for (String route :
        List.of("/api/admin/users", path(customer.id()), path(customer.id()) + "/audit")) {
      mvc.perform(get(route)).andExpect(status().isUnauthorized());
      mvc.perform(get(route).header("Authorization", "Bearer " + customer.token()))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            put(path(customer.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(change(customer.id(), "ADMIN", true))))
        .andExpect(status().isUnauthorized());
    update(customer.id(), customer.token(), change(customer.id(), "ADMIN", true))
        .andExpect(status().isForbidden());
    assertThat(count("user_admin_audit")).isZero();
    assertThat(version(customer.id())).isZero();
  }

  @Test
  void administrativeViewsExposeNoPasswordOrTokenMaterial() throws Exception {
    mvc.perform(get(path(customer.id())).header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(customer.email()))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.accessToken").doesNotExist())
        .andExpect(jsonPath("$.digest").doesNotExist());
    var list =
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(list).doesNotContain("passwordHash", "accessToken", "digest");
  }

  @Test
  void promotionRevokesOldTokensAndRequiresFreshAuthenticationForAdministratorAccess()
      throws Exception {
    String otherToken = login(customer.email());
    update(customer.id(), admin, change(customer.id(), "ADMIN", true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.version").value(1));
    me(customer.token()).andExpect(status().isUnauthorized());
    me(otherToken).andExpect(status().isUnauthorized());
    assertThat(tokens(customer.id())).isZero();
    mvc.perform(
            get("/api/admin/users").header("Authorization", "Bearer " + login(customer.email())))
        .andExpect(status().isOk());
    mvc.perform(get(path(customer.id()) + "/audit").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].actorId").value(adminId))
        .andExpect(jsonPath("$.content[0].before.role").value("CUSTOMER"))
        .andExpect(jsonPath("$.content[0].after.role").value("ADMIN"))
        .andExpect(jsonPath("$.content[0].after.version").value(1))
        .andExpect(jsonPath("$.content[0].revokedTokens").value(2));
  }

  @Test
  void demotionRevokesAllExistingTokensAndFreshLoginHasOnlyCustomerPermissions() throws Exception {
    User target = secondAdmin();
    String secondToken = login(target.email());
    update(target.id(), admin, change(target.id(), "CUSTOMER", true)).andExpect(status().isOk());
    me(target.token()).andExpect(status().isUnauthorized());
    me(secondToken).andExpect(status().isUnauthorized());
    String fresh = login(target.email());
    me(fresh).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CUSTOMER"));
    mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + fresh))
        .andExpect(status().isForbidden());
    assertThat(activeAdmins()).isEqualTo(1);
  }

  @Test
  void disablingRevokesTokensAndPreventsPasswordLogin() throws Exception {
    login(customer.email());
    update(customer.id(), admin, change(customer.id(), "CUSTOMER", false))
        .andExpect(status().isOk());
    me(customer.token()).andExpect(status().isUnauthorized());
    assertThat(tokens(customer.id())).isZero();
    mvc.perform(
            post("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", customer.email(), "password", PASS))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void reactivationNeverRevivesTokensIssuedBeforeDisable() throws Exception {
    update(customer.id(), admin, change(customer.id(), "CUSTOMER", false))
        .andExpect(status().isOk());
    update(customer.id(), admin, change(customer.id(), "CUSTOMER", true))
        .andExpect(status().isOk());
    me(customer.token()).andExpect(status().isUnauthorized());
    me(login(customer.email())).andExpect(status().isOk());
    assertThat(count("user_admin_audit")).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lastActiveAdministratorCannotBeDemotedOrDisabled(boolean demote) throws Exception {
    update(adminId, admin, change(adminId, demote ? "CUSTOMER" : "ADMIN", demote))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("user_state_conflict"));
    assertThat(activeAdmins()).isEqualTo(1);
    assertThat(version(adminId)).isZero();
    me(admin).andExpect(status().isOk());
    assertThat(count("user_admin_audit")).isZero();
  }

  @Test
  void administratorCanDemoteSelfWhenAnotherActiveAdministratorRemains() throws Exception {
    User other = secondAdmin();
    update(adminId, admin, change(adminId, "CUSTOMER", true)).andExpect(status().isOk());
    me(admin).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + other.token()))
        .andExpect(status().isOk());
    me(login(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CUSTOMER"));
    assertThat(activeAdmins()).isEqualTo(1);
  }

  @Test
  void concurrentSelfDisablesCannotRemoveBothRemainingAdministrators() throws Exception {
    User other = secondAdmin();
    var one = change(adminId, "ADMIN", false);
    var two = change(other.id(), "ADMIN", false);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                start.await();
                return update(adminId, admin, one).andReturn().getResponse().getStatus();
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return update(other.id(), other.token(), two).andReturn().getResponse().getStatus();
              });
      start.countDown();
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(activeAdmins()).isEqualTo(1);
      assertThat(count("user_admin_audit")).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentCrossDemotionsRevalidateWaitingOperatorRatherThanTrustStalePrincipal()
      throws Exception {
    User other = secondAdmin();
    Actor a = identity.authenticate(admin).orElseThrow(),
        b = identity.authenticate(other.token()).orElseThrow();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var first =
          pool.submit(
              () -> {
                start.await();
                return serviceChange(
                    other.id(),
                    a,
                    new UserStateChange(
                        0, AppUser.Role.CUSTOMER, true, "Concurrent access review"));
              });
      var second =
          pool.submit(
              () -> {
                start.await();
                return serviceChange(
                    adminId,
                    b,
                    new UserStateChange(
                        0, AppUser.Role.CUSTOMER, true, "Concurrent access review"));
              });
      start.countDown();
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 403);
      assertThat(activeAdmins()).isEqualTo(1);
      assertThat(count("user_admin_audit")).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private int serviceChange(String id, Actor actor, UserStateChange change) {
    try {
      administration.update(id, actor, change);
      return 200;
    } catch (AccessDeniedException denied) {
      return 403;
    }
  }

  @Test
  void staleRevisionDoesNotUndoNewRoleOrRevokeFreshTokens() throws Exception {
    var input = change(customer.id(), "ADMIN", true);
    update(customer.id(), admin, input).andExpect(status().isOk());
    String fresh = login(customer.email());
    input.put("role", "CUSTOMER");
    update(customer.id(), admin, input).andExpect(status().isConflict());
    me(fresh).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
    assertThat(count("user_admin_audit")).isEqualTo(1);
  }

  @Test
  void noOpKeepsVersionTokensAndAuditUnchanged() throws Exception {
    update(customer.id(), admin, change(customer.id(), "CUSTOMER", true))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0));
    assertThat(count("user_admin_audit")).isZero();
    assertThat(tokens(customer.id())).isEqualTo(1);
    me(customer.token()).andExpect(status().isOk());
  }

  @Test
  void failedAuditInsertionRollsBackStateVersionAndTokenRevocation() throws Exception {
    jdbc.execute(
        "CREATE TRIGGER reject_user_audit BEFORE INSERT ON user_admin_audit FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected user audit failure'");
    try {
      update(customer.id(), admin, change(customer.id(), "ADMIN", false))
          .andExpect(status().isInternalServerError());
      assertThat(version(customer.id())).isZero();
      assertThat(count("user_admin_audit")).isZero();
      me(customer.token())
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.role").value("CUSTOMER"));
      assertThat(tokens(customer.id())).isEqualTo(1);
      assertThat(activeAdmins()).isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER reject_user_audit");
    }
    update(customer.id(), admin, change(customer.id(), "ADMIN", false)).andExpect(status().isOk());
    me(customer.token()).andExpect(status().isUnauthorized());
  }

  @Test
  void missingMembershipGuardFailsClosedAndCanBeRestoredWithoutPartialChanges() throws Exception {
    jdbc.update("DELETE FROM identity_admin_guard");
    try {
      update(customer.id(), admin, change(customer.id(), "ADMIN", false))
          .andExpect(status().isInternalServerError());
      assertThat(version(customer.id())).isZero();
      assertThat(count("user_admin_audit")).isZero();
      me(customer.token()).andExpect(status().isOk());
      assertThatThrownBy(() -> identity.bootstrap("blocked-bootstrap@example.test", PASS))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      jdbc.update("INSERT INTO identity_admin_guard(id) VALUES(1)");
    }
    update(customer.id(), admin, change(customer.id(), "ADMIN", true)).andExpect(status().isOk());
  }

  @Test
  void loginAlreadyInProgressCommitsBeforeRevocationAndItsNewTokenIsRemoved() throws Exception {
    Actor operator = identity.authenticate(admin).orElseThrow();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    passwords.arm();
    try {
      var issuance = pool.submit(() -> identity.login(customer.email(), PASS).accessToken());
      assertThat(passwords.entered.await(10, TimeUnit.SECONDS)).isTrue();
      var disabling =
          pool.submit(
              () ->
                  administration.update(
                      customer.id(),
                      operator,
                      new UserStateChange(
                          0, AppUser.Role.CUSTOMER, false, "Disable during login")));
      assertThatThrownBy(() -> disabling.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      passwords.release();
      String issued = issuance.get(20, TimeUnit.SECONDS);
      assertThat(disabling.get(20, TimeUnit.SECONDS).active()).isFalse();
      assertThat(identity.authenticate(issued)).isEmpty();
      assertThat(tokens(customer.id())).isZero();
    } finally {
      passwords.release();
      pool.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void bootstrapCannotSilentlyRestoreDemotedOrDisabledConfiguredAccount(boolean demote)
      throws Exception {
    User other = secondAdmin();
    update(adminId, other.token(), change(adminId, demote ? "CUSTOMER" : "ADMIN", demote))
        .andExpect(status().isOk());
    assertThatThrownBy(() -> identity.bootstrap(ADMIN, "another-synthetic-bootstrap-fixture"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(version(adminId)).isEqualTo(1);
    assertThat(activeAdmins()).isEqualTo(1);
    me(admin).andExpect(status().isUnauthorized());
  }

  @Test
  void concurrentBootstrapCreatesOneAdministratorAndPreservesExistingCredentials()
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                start.await();
                identity.bootstrap("new-bootstrap@example.test", PASS);
                return true;
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                identity.bootstrap("new-bootstrap@example.test", PASS);
                return true;
              });
      start.countDown();
      assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(b.get(20, TimeUnit.SECONDS)).isTrue();
      assertThat(activeAdmins()).isEqualTo(2);
      identity.bootstrap("new-bootstrap@example.test", "ignored-synthetic-bootstrap-fixture");
      me(login("new-bootstrap@example.test")).andExpect(status().isOk());
      assertThat(count("user_admin_audit")).isZero();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void listAndAuditPaginationAreBoundedFilteredAndVersionOrdered() throws Exception {
    update(customer.id(), admin, change(customer.id(), "CUSTOMER", false))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/admin/users")
                .param("role", "CUSTOMER")
                .param("active", "false")
                .param("size", "1")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(customer.id()));
    update(customer.id(), admin, change(customer.id(), "CUSTOMER", true))
        .andExpect(status().isOk());
    mvc.perform(
            get(path(customer.id()) + "/audit")
                .param("size", "1")
                .param("page", "1")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].after.version").value(1));
    for (String route : List.of("/api/admin/users", path(customer.id()) + "/audit")) {
      mvc.perform(get(route).param("size", "101").header("Authorization", "Bearer " + admin))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            get("/api/admin/users")
                .param("role", "ROOT")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isBadRequest());
  }

  @Test
  void requiredStateFieldsAreValidatedAndCredentialIdentityFieldsCannotBeAssigned()
      throws Exception {
    for (String field : List.of("expectedVersion", "role", "active", "reason")) {
      var input = change(customer.id(), "CUSTOMER", true);
      input.remove(field);
      update(customer.id(), admin, input).andExpect(status().isBadRequest());
    }
    var invalid = change(customer.id(), "ROOT", true);
    update(customer.id(), admin, invalid).andExpect(status().isBadRequest());
    var forged = change(customer.id(), "CUSTOMER", true);
    forged.put("email", "forged@example.test");
    forged.put("password", "forged-credential-fixture");
    forged.put("passwordHash", "not-a-hash");
    forged.put("id", UUID.randomUUID().toString());
    forged.put("version", 100);
    update(customer.id(), admin, forged)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(customer.email()))
        .andExpect(jsonPath("$.id").value(customer.id()))
        .andExpect(jsonPath("$.version").value(0));
    me(login(customer.email())).andExpect(status().isOk());
    assertThat(count("user_admin_audit")).isZero();
  }

  @Test
  void missingUsersReturnNotFoundWithoutAuditOrTokenChanges() throws Exception {
    String missing = UUID.randomUUID().toString();
    for (String route : List.of(path(missing), path(missing) + "/audit"))
      mvc.perform(get(route).header("Authorization", "Bearer " + admin))
          .andExpect(status().isNotFound());
    var input =
        new HashMap<String, Object>(
            Map.of(
                "expectedVersion", 0, "role", "ADMIN", "active", true, "reason", "Access review"));
    update(missing, admin, input).andExpect(status().isNotFound());
    assertThat(count("user_admin_audit")).isZero();
    me(customer.token()).andExpect(status().isOk());
  }

  @Test
  void openApiDocumentsAdministratorIdentityOperations() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.paths['/api/admin/users/{id}'].put.security[0].bearerAuth").isArray())
        .andExpect(
            jsonPath("$.paths['/api/admin/users/{id}/audit'].get.security[0].bearerAuth")
                .isArray());
  }
}
