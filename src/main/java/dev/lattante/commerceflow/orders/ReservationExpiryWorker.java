package dev.lattante.commerceflow.orders;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "commerceflow.reservations.scheduling-enabled",
    havingValue = "true", matchIfMissing = true)
class ReservationExpiryWorker {
  private static final Logger LOG = LoggerFactory.getLogger(ReservationExpiryWorker.class);
  private final OrderLifecycleService lifecycle;

  ReservationExpiryWorker(OrderLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Scheduled(fixedDelayString = "${commerceflow.reservations.scan-delay-ms:60000}",
      initialDelayString = "${commerceflow.reservations.scan-delay-ms:60000}")
  public void expireDue() {
    try {
      for (String id : lifecycle.dueCandidates()) {
        try {
          lifecycle.expire(id);
        } catch (RuntimeException failure) {
          // A failed order rolls back independently and remains eligible for the next scan.
          LOG.warn("Reservation expiry deferred: {}", failure.getClass().getSimpleName());
        }
      }
    } catch (RuntimeException failure) {
      LOG.warn("Reservation scan deferred: {}", failure.getClass().getSimpleName());
    }
  }
}
