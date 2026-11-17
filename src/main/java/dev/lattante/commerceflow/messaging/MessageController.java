package dev.lattante.commerceflow.messaging;

import dev.lattante.commerceflow.identity.Actor;
import dev.lattante.commerceflow.orders.OrderService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name="bearerAuth")
class MessageController {
  private final OrderInbox inbox;
  private final OrderService orders;
  private final MessageOperations operations;
  MessageController(OrderInbox inbox,OrderService orders,MessageOperations operations) {
    this.inbox=inbox;this.orders=orders;this.operations=operations;
  }
  @GetMapping("/api/orders/{id}/projection")
  OrderInbox.Projection projection(@PathVariable UUID id,@AuthenticationPrincipal Actor actor) {
    orders.find(id.toString(),actor);
    return inbox.find(id.toString());
  }
  @GetMapping("/api/admin/messaging/failures")
  List<MessageOperations.Delivery> failures(@RequestParam(defaultValue="20") int size) {
    return operations.failures(size);
  }
  @GetMapping("/api/admin/messaging/dead-letters")
  List<MessageOperations.DeadLetter> deadLetters(@RequestParam(defaultValue="20") int size) {
    return operations.deadLetters(size);
  }
  @PostMapping("/api/admin/messaging/dead-letters/{id}/replay")
  MessageOperations.Delivery replay(@PathVariable String id,@RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal Actor actor) {
    return operations.replay(id,actor,key);
  }
  @PostMapping("/api/admin/messaging/failures/{id}/retry")
  MessageOperations.Delivery retry(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,
      @AuthenticationPrincipal Actor actor) {
    return operations.retry(id.toString(),actor,key);
  }
}
