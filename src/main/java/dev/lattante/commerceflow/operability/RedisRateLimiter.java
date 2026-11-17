package dev.lattante.commerceflow.operability;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "commerceflow.rate-limit.enabled", havingValue = "true")
public class RedisRateLimiter {
  private static final DefaultRedisScript<List> SCRIPT =
      new DefaultRedisScript<>(
          """
          local limit = tonumber(ARGV[2])
          local ttl = redis.call('PTTL', KEYS[1])
          local current = tonumber(redis.call('GET', KEYS[1]) or '0')
          if current >= limit and ttl > 0 then
            return {0, current, ttl}
          end
          current = redis.call('INCR', KEYS[1])
          if current == 1 or ttl < 0 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            ttl = tonumber(ARGV[1])
          end
          return {1, current, ttl}
          """,
          List.class);
  private final StringRedisTemplate redis;
  private final RateLimitSettings settings;
  private final MeterRegistry metrics;

  public RedisRateLimiter(
      StringRedisTemplate redis, RateLimitSettings settings, MeterRegistry metrics) {
    this.redis = redis;
    this.settings = settings;
    this.metrics = metrics;
  }

  public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {}

  public static class Unavailable extends RuntimeException {}

  public Decision acquire(RateLimitSettings.Policy policy, String identity) {
    try {
      List<?> values =
          redis.execute(
              SCRIPT,
              List.of(key(policy, identity)),
              Long.toString(settings.windowSeconds() * 1000L),
              Integer.toString(settings.limit(policy)));
      if (values == null || values.size() != 3)
        throw new IllegalStateException("Invalid limiter result");
      boolean allowed = ((Number) values.get(0)).longValue() == 1;
      int remaining = Math.max(0, settings.limit(policy) - ((Number) values.get(1)).intValue());
      long retry = Math.max(1, (((Number) values.get(2)).longValue() + 999) / 1000);
      count(policy, allowed ? "allowed" : "denied");
      return new Decision(allowed, remaining, retry);
    } catch (RuntimeException unavailable) {
      count(policy, "unavailable");
      throw new Unavailable();
    }
  }

  public boolean available() {
    try {
      return "PONG"
          .equals(
              redis.execute(
                  (org.springframework.data.redis.core.RedisCallback<String>)
                      connection -> connection.ping()));
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  static String key(RateLimitSettings.Policy policy, String identity) {
    Objects.requireNonNull(identity);
    try {
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(identity.getBytes(StandardCharsets.UTF_8)));
      return "cf:rate:" + policy.name().toLowerCase(Locale.ROOT) + ":" + digest;
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void count(RateLimitSettings.Policy policy, String outcome) {
    metrics
        .counter(
            "commerceflow.rate.limit.requests",
            "policy",
            policy.name().toLowerCase(Locale.ROOT),
            "outcome",
            outcome)
        .increment();
  }
}
