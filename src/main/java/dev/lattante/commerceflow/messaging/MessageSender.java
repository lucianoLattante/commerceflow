package dev.lattante.commerceflow.messaging;

public interface MessageSender {
  void send(String topic, String key, String payload);
}
