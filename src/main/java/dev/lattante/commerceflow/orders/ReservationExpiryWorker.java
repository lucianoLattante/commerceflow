package dev.lattante.commerceflow.orders;

import java.time.Clock;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "commerceflow.reservations.scheduling-enabled",
    havingValue = "true", matchIfMissing = true)
class ReservationExpiryWorker {
  private static final Logger LOG = LoggerFactory.getLogger(ReservationExpiryWorker.class);
  private final OrderRepository orders;
  private final OrderLifecycleService lifecycle;
  private final Clock clock;

  ReservationExpiryWorker(OrderRepository orders, OrderLifecycleService lifecycle, Clock clock) {
    this.orders = orders;
    this.lifecycle = lifecycle;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${commerceflow.reservations.scan-delay-ms:60000}",
      initialDelayString = "${commerceflow.reservations.scan-delay-ms:60000}")
  public void expireDue() {
    try {
      for (String id : orders.dueIds(clock.instant(), PageRequest.of(0, 100))) {
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
