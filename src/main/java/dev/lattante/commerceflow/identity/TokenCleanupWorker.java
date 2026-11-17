package dev.lattante.commerceflow.identity;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "commerceflow.identity.cleanup.enabled", havingValue = "true")
public class TokenCleanupWorker {
  private final ExpiredTokenCleanup cleanup;
  private final TokenCleanupSettings settings;
  private final MeterRegistry metrics;

  public TokenCleanupWorker(
      ExpiredTokenCleanup cleanup, TokenCleanupSettings settings, MeterRegistry metrics) {
    this.cleanup = cleanup;
    this.settings = settings;
    this.metrics = metrics;
  }

  @Scheduled(
      fixedDelayString = "${commerceflow.identity.cleanup.interval-ms:60000}",
      initialDelayString =
          "${commerceflow.identity.cleanup.initial-delay-ms:${commerceflow.identity.cleanup.interval-ms:60000}}")
  public void tick() {
    try {
      cleanup.clean(settings.batchSize());
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(TokenCleanupWorker.class)
          .warn("Token cleanup deferred: {}", failure.getClass().getSimpleName());
      try {
        metrics.counter("commerceflow.identity.cleanup.runs", "outcome", "failed").increment();
      } catch (RuntimeException observationFailure) {
        org.slf4j.LoggerFactory.getLogger(TokenCleanupWorker.class)
            .warn(
                "Token cleanup failure metric unavailable: {}",
                observationFailure.getClass().getSimpleName());
      }
    }
  }
}
