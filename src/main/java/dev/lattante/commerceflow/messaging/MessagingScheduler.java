package dev.lattante.commerceflow.messaging;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnExpression("${commerceflow.messaging.enabled:false} && ${commerceflow.messaging.scheduling-enabled:true}")
class MessagingScheduler {
  private static final Logger LOG=LoggerFactory.getLogger(MessagingScheduler.class);
  private final MessageDispatcher dispatcher;
  MessagingScheduler(MessageDispatcher dispatcher) { this.dispatcher=dispatcher; }

  @Scheduled(fixedDelayString="${commerceflow.messaging.scan-delay-ms:1000}",
      initialDelayString="${commerceflow.messaging.scan-delay-ms:1000}")
  public void publish() {
    for(int i=0;i<10;i++) {
      try { if(!dispatcher.dispatchOnce()) return; }
      catch(RuntimeException failure) {
        LOG.warn("Message dispatch deferred: {}",failure.getClass().getSimpleName());
      }
    }
  }
}
