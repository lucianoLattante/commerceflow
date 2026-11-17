package dev.lattante.commerceflow.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_users")
public class AppUser {
  public enum Role {
    ADMIN,
    CUSTOMER
  }

  @Id
  @Column(length = 36)
  private String id;

  @Column(nullable = false, unique = true, length = 254)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 16)
  private Role role;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Version private long version;

  protected AppUser() {}

  public AppUser(String email, String passwordHash, Role role, Instant now) {
    this.id = UUID.randomUUID().toString();
    this.email = email;
    this.passwordHash = passwordHash;
    this.role = role;
    this.active = true;
    this.createdAt = now;
  }

  public String getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Role getRole() {
    return role;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  void changeState(UserStateChange change) {
    if (version != change.expectedVersion())
      throw new UserStateConflict("User changed; read the current version and reconcile");
    role = change.role();
    active = change.active();
  }

  public boolean isActive() {
    return active;
  }
}
