package dev.lattante.commerceflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.lattante.commerceflow.identity.IdentityService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = {"CF_BOOTSTRAP_ADMIN_EMAIL=", "CF_BOOTSTRAP_ADMIN_PASSWORD="})
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@Testcontainers
class CommerceFlowIT {
  private static final String ADMIN = "admin@example.test";
  private static final String PASS = "local-only-test-fixture";

  @Container
  static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.4.11")
          .withDatabaseName("commerceflow_test")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired IdentityService identity;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM product_change_audit");
    jdbc.update("DELETE FROM access_tokens");
    jdbc.update("DELETE FROM products");
    jdbc.update("DELETE FROM app_users");
    identity.bootstrap(ADMIN, PASS);
  }

  private String body(Object value) throws Exception {
    return json.writeValueAsString(value);
  }

  private String token(String email) throws Exception {
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

  private String customer() throws Exception {
    mvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        Map.of(
                            "email", "customer@example.test", "password", PASS, "role", "ADMIN"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("CUSTOMER"));
    return token("customer@example.test");
  }

  private Map<String, Object> product(String sku, String name) {
    return Map.of("sku", sku, "name", name, "amount", "12.34", "currency", "USD", "stock", 10);
  }

  private ResultActions create(String access, Map<String, Object> product) throws Exception {
    return mvc.perform(
        post("/api/products")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(product)));
  }

  private long productCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
  }

  @Test
  void flywayRunsAndHibernateValidatesRealMysql() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Long.class))
        .isEqualTo(6);
  }

  @Test
  void registrationCannotChooseAdministratorRole() throws Exception {
    String access = customer();
    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("CUSTOMER"))
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
  }

  @Test
  void basicCredentialsAreNotAnAlternativeToBearerAuthentication() throws Exception {
    mvc.perform(
            get("/api/users/me")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.httpBasic(ADMIN, PASS)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anonymousWriteIsRejectedWithoutPersisting() throws Exception {
    mvc.perform(
            post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(product("SKU-1", "Book"))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("unauthorized"));
    assertThat(productCount()).isZero();
  }

  @Test
  void customerCannotWriteCatalog() throws Exception {
    create(customer(), product("SKU-1", "Book")).andExpect(status().isForbidden());
    assertThat(productCount()).isZero();
  }

  @Test
  void administratorCreatesProductWithExactPriceAndAvailability() throws Exception {
    create(token(ADMIN), product("SKU-1", "Book"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.amount").value(12.34))
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.available").value(10));
  }

  @Test
  void publicCatalogIsPaginatedAndSorted() throws Exception {
    String access = token(ADMIN);
    create(access, product("B", "Second")).andExpect(status().isCreated());
    create(access, product("A", "First")).andExpect(status().isCreated());
    mvc.perform(get("/api/products").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].sku").value("A"))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(1));
  }

  @Test
  void searchTreatsWildcardsAsLiteralText() throws Exception {
    String access = token(ADMIN);
    create(access, product("A", "100% Cotton")).andExpect(status().isCreated());
    create(access, product("B", "1000 Poly")).andExpect(status().isCreated());
    mvc.perform(get("/api/products").param("q", "%"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].sku").value("A"));
  }

  @Test
  void duplicateSkuIsAConflictWithoutSqlDisclosure() throws Exception {
    String access = token(ADMIN);
    create(access, product("A", "First")).andExpect(status().isCreated());
    create(access, product("A", "Second"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("conflict"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Duplicate entry"))));
    assertThat(productCount()).isEqualTo(1);
  }

  @Test
  void negativeStockCannotPersist() throws Exception {
    var data = new HashMap<>(product("A", "Book"));
    data.put("stock", -1);
    create(token(ADMIN), data).andExpect(status().isBadRequest());
    assertThat(productCount()).isZero();
  }

  @Test
  void fractionalCentsCannotPersist() throws Exception {
    var data = new HashMap<>(product("A", "Book"));
    data.put("amount", "1.001");
    create(token(ADMIN), data).andExpect(status().isBadRequest());
    assertThat(productCount()).isZero();
  }

  @Test
  void utf8PasswordBeyondBcryptLimitIsRejected() throws Exception {
    mvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", "new@example.test", "password", "é".repeat(40)))))
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users", Long.class)).isEqualTo(1);
  }

  @Test
  void duplicateEmailIsNormalizedAndRejected() throws Exception {
    mvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", ADMIN.toUpperCase(Locale.ROOT), "password", PASS))))
        .andExpect(status().isConflict());
  }

  @Test
  void tokenIsStoredOnlyAsADigest() throws Exception {
    String access = token(ADMIN);
    assertThat(access).matches("cf_[A-Za-z0-9_-]{43}");
    String digest = jdbc.queryForObject("SELECT digest FROM access_tokens", String.class);
    assertThat(digest).matches("[0-9a-f]{64}").isNotEqualTo(access);
  }

  @Test
  void revokedTokenCannotBeReused() throws Exception {
    String access = token(ADMIN);
    mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + access))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenIsRejected() throws Exception {
    String access = token(ADMIN);
    jdbc.update(
        "UPDATE access_tokens SET expires_at = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void disabledUserIsRejectedImmediately() throws Exception {
    String access = token(ADMIN);
    jdbc.update("UPDATE app_users SET active = false");
    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void changedRolesApplyToAlreadyIssuedTokens() throws Exception {
    String access = token(ADMIN);
    jdbc.update("UPDATE app_users SET role = 'CUSTOMER'");
    create(access, product("A", "Book")).andExpect(status().isForbidden());
    assertThat(productCount()).isZero();
  }

  @Test
  void invalidCredentialsDoNotRevealWhetherUserExists() throws Exception {
    for (String email : List.of(ADMIN, "missing@example.test"))
      mvc.perform(
              post("/api/auth/token")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(Map.of("email", email, "password", "incorrect-test-fixture"))))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.detail").value("Authentication required or invalid"));
  }

  @Test
  void invalidPaginationReturnsAClientError() throws Exception {
    mvc.perform(get("/api/products").param("size", "101")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/products").param("page", "-1")).andExpect(status().isBadRequest());
  }

  @Test
  void malformedJsonDoesNotExposeInternals() throws Exception {
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_request"))
        .andExpect(jsonPath("$.trace").doesNotExist());
  }

  @Test
  void openApiDescribesTheApi() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.paths['/api/users/me'].get.security[0].bearerAuth").isArray())
        .andExpect(jsonPath("$.paths['/api/auth/logout'].post.security[0].bearerAuth").isArray());
  }

  @Test
  void metricsAreNotPublic() throws Exception {
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + customer()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + token(ADMIN)))
        .andExpect(status().isOk());
  }

  @Test
  void mysqlAlsoEnforcesStockConstraints() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO"
                        + " products(id,sku,name,amount,currency,on_hand,reserved,version,created_at)"
                        + " VALUES(?,?,?,1.00,'USD',1,2,0,CURRENT_TIMESTAMP(6))",
                    UUID.randomUUID().toString(),
                    "INVALID",
                    "Invalid"))
        .isInstanceOf(DataAccessException.class)
        .hasRootCauseInstanceOf(java.sql.SQLException.class)
        .satisfies(
            error -> {
              var sql =
                  (java.sql.SQLException) ((DataAccessException) error).getMostSpecificCause();
              assertThat(sql.getErrorCode()).isEqualTo(3819);
            });
    assertThat(productCount()).isZero();
  }
}
