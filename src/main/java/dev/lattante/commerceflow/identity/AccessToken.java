package dev.lattante.commerceflow.identity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "access_tokens")
class AccessToken {
  @Id
  @Column(length = 64)
  String digest;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id")
  AppUser user;

  @Column(name = "expires_at", nullable = false)
  Instant expiresAt;

  protected AccessToken() {}

  AccessToken(String digest, AppUser user, Instant expiry) {
    this.digest = digest;
    this.user = user;
    this.expiresAt = expiry;
  }
}
