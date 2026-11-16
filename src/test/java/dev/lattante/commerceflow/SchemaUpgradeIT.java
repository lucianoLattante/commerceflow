package dev.lattante.commerceflow;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class SchemaUpgradeIT {
  @Container
  static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.4.11")
          .withDatabaseName("upgrade_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradingV1PreservesExistingIdentityAndCatalogData() throws Exception {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("1")
        .load()
        .migrate();
    String user = UUID.randomUUID().toString(), product = UUID.randomUUID().toString();
    try (Connection connection =
        DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO app_users(id,email,password_hash,role,active,created_at)"
                  + " VALUES(?,?,'synthetic-unused-hash','CUSTOMER',true,CURRENT_TIMESTAMP(6))")) {
        insert.setString(1, user);
        insert.setString(2, "upgrade@example.test");
        insert.executeUpdate();
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO"
                  + " products(id,sku,name,amount,currency,on_hand,reserved,version,created_at)"
                  + " VALUES(?,'UPGRADE-SKU','Existing"
                  + " product',12.34,'USD',7,0,0,CURRENT_TIMESTAMP(6))")) {
        insert.setString(1, product);
        insert.executeUpdate();
      }
    }
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .load()
        .migrate();
    try (Connection connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Statement statement = connection.createStatement()) {
      try (ResultSet row = statement.executeQuery("SELECT email FROM app_users")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString(1)).isEqualTo("upgrade@example.test");
      }
      try (ResultSet row =
          statement.executeQuery("SELECT sku,amount,on_hand,reserved FROM products")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString(1)).isEqualTo("UPGRADE-SKU");
        assertThat(row.getBigDecimal(2)).isEqualByComparingTo("12.34");
        assertThat(row.getInt(3)).isEqualTo(7);
        assertThat(row.getInt(4)).isZero();
      }
      try (ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM customer_orders")) {
        row.next();
        assertThat(row.getLong(1)).isZero();
      }
      try (ResultSet row =
          statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")) {
        row.next();
        assertThat(row.getLong(1)).isEqualTo(2);
      }
    }
  }
}
