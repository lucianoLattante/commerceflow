package dev.lattante.commerceflow.identity;

import dev.lattante.commerceflow.shared.DomainViolation;

public record UserStateChange(
    long expectedVersion, AppUser.Role role, boolean active, String reason) {
  public UserStateChange {
    if (expectedVersion < 0
        || role == null
        || reason == null
        || reason.isBlank()
        || reason.length() > 160) throw new DomainViolation("Invalid user state change");
    reason = reason.strip();
  }
}
