package dev.lattante.commerceflow.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "commerceflow.messaging.enabled", havingValue = "true")
public class MessageDispatcher {
  private final DispatchStore store;
  private final MessageSender sender;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public MessageDispatcher(
      DispatchStore store,
      MessageSender sender,
      io.micrometer.core.instrument.MeterRegistry metrics) {
    this.metrics = metrics;
    this.store = store;
    this.sender = sender;
  }

  public boolean dispatchOnce() {
    var candidate = store.claim();
    if (candidate.isEmpty()) return false;
    var lease = candidate.get();
    try {
      sender.send(lease.topic(), lease.key(), lease.payload());
    } catch (RuntimeException unavailable) {
      observe("commerceflow.dispatch.attempts", "transport_failed");
      store.failed(lease);
      return true;
    }
    observe("commerceflow.dispatch.attempts", "acknowledged");
    // An acknowledgement followed by a DB failure retains the lease. Recovery can duplicate
    // delivery.
    try {
      if (!store.complete(lease)) observe("commerceflow.dispatch.bookkeeping", "stale_lease");
    } catch (RuntimeException markFailure) {
      observe("commerceflow.dispatch.bookkeeping", "mark_failed");
      throw markFailure;
    }
    return true;
  }

  private void observe(String name, String outcome) {
    try {
      metrics.counter(name, "outcome", outcome).increment();
    } catch (RuntimeException observationFailure) {
      org.slf4j.LoggerFactory.getLogger(MessageDispatcher.class)
          .warn("Dispatch metric unavailable: {}", observationFailure.getClass().getSimpleName());
    }
  }
}
