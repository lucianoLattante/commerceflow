package dev.lattante.commerceflow.operability;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitSettings.class)
public class OperabilityConfiguration {
  @Bean("admission")
  HealthIndicator admission(ObjectProvider<RedisRateLimiter> limiter) {
    return () -> {
      var active = limiter.getIfAvailable();
      if (active == null) return Health.up().withDetail("mode", "disabled").build();
      return active.available() ? Health.up().build() : Health.outOfService().build();
    };
  }
}
