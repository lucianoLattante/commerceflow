package dev.lattante.commerceflow.catalog;

import dev.lattante.commerceflow.inventory.Stock;
import dev.lattante.commerceflow.shared.Money;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "products")
class Product {
  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false, unique = true, length = 64)
  private String sku;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 3)
  private Money.Currency currency;

  @Column(name = "on_hand", nullable = false)
  private int onHand;

  @Column(nullable = false)
  private int reserved;

  @Column(nullable = false)
  private boolean active = true;

  @Version private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Product() {}

  Product(String sku, String name, Money price, int quantity, Instant now) {
    this.id = UUID.randomUUID().toString();
    this.sku = sku;
    this.name = name;
    this.amount = price.amount();
    this.currency = price.currency();
    this.onHand = new Stock(quantity, 0).onHand();
    this.createdAt = now;
  }

  String getId() {
    return id;
  }

  String getSku() {
    return sku;
  }

  String getName() {
    return name;
  }

  BigDecimal getAmount() {
    return amount;
  }

  Money.Currency getCurrency() {
    return currency;
  }

  boolean isActive() {
    return active;
  }

  long getVersion() {
    return version;
  }

  boolean revise(ProductEdit edit) {
    if (version != edit.expectedVersion())
      throw new ProductConflict("Product changed; read the current version and reconcile");
    if (edit.onHand() < reserved)
      throw new ProductConflict("On-hand stock cannot be lower than existing reservations");
    if (name.equals(edit.name())
        && amount.compareTo(edit.amount()) == 0
        && currency == edit.currency()
        && onHand == edit.onHand()
        && active == edit.active()) return false;
    name = edit.name();
    amount = edit.amount();
    currency = edit.currency();
    onHand = edit.onHand();
    active = edit.active();
    return true;
  }

  void reserve(int quantity) {
    if (!active) throw new ProductConflict("Product is inactive");
    Stock next = getStock().reserve(quantity);
    this.reserved = next.reserved();
  }

  void release(int quantity) {
    Stock next = getStock().release(quantity);
    this.reserved = next.reserved();
  }

  void consume(int quantity) {
    Stock next = getStock().commit(quantity);
    this.onHand = next.onHand();
    this.reserved = next.reserved();
  }

  Stock getStock() {
    return new Stock(onHand, reserved);
  }
}
