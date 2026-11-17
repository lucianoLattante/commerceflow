package dev.lattante.commerceflow.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="commerceflow.messaging.enabled",havingValue="true")
class OrderProjectionListener {
  private final OrderInbox inbox;
  OrderProjectionListener(OrderInbox inbox) { this.inbox=inbox; }

  @KafkaListener(id="order-projection",groupId="commerceflow-order-projection-v1",
      topics=OrderMessageCodec.TOPIC,containerFactory="orderKafkaListenerContainerFactory",
      autoStartup="${commerceflow.messaging.listener-auto-startup:true}")
  public void receive(ConsumerRecord<String,String> record,Acknowledgment ack) {
    inbox.apply(record.key(),record.value());
    // MySQL has committed before the Kafka acknowledgement. A crash here can redeliver safely.
    ack.acknowledge();
  }
}
