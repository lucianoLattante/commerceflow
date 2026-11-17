package dev.lattante.commerceflow.operability;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("commerceflow.rate-limit")
@Validated
public record RateLimitSettings(
    @DefaultValue("60") @Min(1) @Max(3600) int windowSeconds,
    @DefaultValue("5") @Min(1) @Max(10000) int loginLimit,
    @DefaultValue("3") @Min(1) @Max(10000) int registrationLimit,
    @DefaultValue("60") @Min(1) @Max(10000) int writeLimit) {
  public enum Policy {
    LOGIN,
    REGISTRATION,
    WRITE
  }

  public int limit(Policy policy) {
    return switch (policy) {
      case LOGIN -> loginLimit;
      case REGISTRATION -> registrationLimit;
      case WRITE -> writeLimit;
    };
  }
}
