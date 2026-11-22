package dev.lattante.commerceflow.orders;

import dev.lattante.commerceflow.identity.Actor;
import dev.lattante.commerceflow.inventory.InventoryReservation.Demand;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@SecurityRequirement(name = "bearerAuth")
class OrderController {
  private final OrderService orders;
  private final OrderLifecycleService lifecycle;

  OrderController(OrderService orders, OrderLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
    this.orders = orders;
  }

  record Item(@NotNull UUID productId, @Min(1) @Max(1000) int quantity) {}

  record CreateOrder(@NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items) {}

  @PostMapping
  ResponseEntity<OrderService.OrderView> create(
      @AuthenticationPrincipal Actor actor,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateOrder input) {
    var request =
        new OrderSubmission(
            input.items().stream().map(i -> new Demand(i.productId(), i.quantity())).toList());
    var result = orders.create(actor, key, request);
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .location(URI.create("/api/orders/" + result.order().id()))
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .body(result.order());
  }

  record SimulatedPayment(
      @NotNull @Schema(example = "APPROVED", description = "Synthetic decision: APPROVED or DECLINED") PaymentDecision decision) {}

  @PostMapping("/{id}/simulated-payment")
  @Operation(summary = "Simulate payment for an owned order",
      description = "Use the order UUID returned by creation, the same owner's Bearer token, and an 8-128 character Idempotency-Key. Repeat the same key/decision only to retry the same payment. Reservations expire after 15 minutes.")
  ResponseEntity<OrderService.OrderView> pay(
      @Parameter(description = "Order UUID returned by POST /api/orders") @PathVariable UUID id,
      @AuthenticationPrincipal Actor actor,
      @Parameter(description = "8-128 ASCII letters, digits, dots, colons, underscores or hyphens. Keep unchanged on retry.",
          example = "demo-payment-001",
          schema = @Schema(type = "string", minLength = 8, maxLength = 128, pattern = "[A-Za-z0-9._:-]{8,128}"))
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody SimulatedPayment input) {
    var result = lifecycle.pay(id.toString(), actor, key, input.decision());
    return ResponseEntity.ok()
        .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
        .body(result.order());
  }

  @PostMapping("/{id}/cancel")
  OrderService.OrderView cancel(@PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
    return lifecycle.cancel(id.toString(), actor);
  }

  @GetMapping("/{id}")
  OrderService.OrderView find(@PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
    return orders.find(id.toString(), actor);
  }

  @GetMapping
  OrderService.OrderPage list(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return orders.list(actor, status, page, size);
  }
}
