package dev.lattante.commerceflow.messaging;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods=false)
@EnableKafka
@ConditionalOnProperty(name="commerceflow.messaging.enabled",havingValue="true")
public class KafkaMessagingConfiguration {
  @Bean
  ProducerFactory<String,String> producerFactory(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    Map<String,Object> p = new HashMap<>();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    p.put(ProducerConfig.ACKS_CONFIG,"all");
    p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
    p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,5000);
    p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,3000);
    p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,10000);
    p.put(ProducerConfig.LINGER_MS_CONFIG,0);
    return new DefaultKafkaProducerFactory<>(p);
  }

  @Bean
  KafkaTemplate<String,String> kafkaTemplate(ProducerFactory<String,String> factory) {
    return new KafkaTemplate<>(factory);
  }

  @Bean
  MessageSender kafkaSender(KafkaTemplate<String,String> template) {
    return (topic,key,payload) -> {
      try { template.send(topic,key,payload).get(12,TimeUnit.SECONDS); }
      catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Kafka send interrupted",interrupted);
      } catch (Exception unavailable) { throw new IllegalStateException("Kafka send unavailable",unavailable); }
    };
  }

  @Bean
  KafkaAdmin.NewTopics messageTopics() {
    return new KafkaAdmin.NewTopics(new NewTopic(OrderMessageCodec.TOPIC,3,(short)1),
        new NewTopic(OrderMessageCodec.DLQ,3,(short)1));
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String,String> orderKafkaListenerContainerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrap, DeadLetters deadLetters) {
    Map<String,Object> p=new HashMap<>();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap);
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
    p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,20);
    var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();
    factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(p));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.getContainerProperties().setSyncCommits(true);
    var errors=new DefaultErrorHandler((record,failure)->deadLetters.capture(record.topic(),
        record.partition(),record.offset(),(String)record.key(),(String)record.value()),
        new FixedBackOff(250L,2L));
    errors.addNotRetryableExceptions(MessageFailure.class);
    errors.setCommitRecovered(true);
    factory.setCommonErrorHandler(errors);
    return factory;
  }
}
