package dev.lattante.commerceflow.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="commerceflow.messaging.enabled", havingValue="true")
public class MessageDispatcher {
  private final DispatchStore store;
  private final MessageSender sender;
  public MessageDispatcher(DispatchStore store, MessageSender sender) {
    this.store = store; this.sender = sender;
  }
  public boolean dispatchOnce() {
    var candidate = store.claim();
    if (candidate.isEmpty()) return false;
    var lease = candidate.get();
    try { sender.send(lease.topic(), lease.key(), lease.payload()); }
    catch (RuntimeException unavailable) { store.failed(lease); return true; }
    // An acknowledgement followed by a DB failure retains the lease. Recovery can duplicate delivery.
    store.complete(lease);
    return true;
  }
}
