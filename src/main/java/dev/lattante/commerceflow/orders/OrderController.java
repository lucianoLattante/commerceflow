package dev.lattante.commerceflow.orders;

import dev.lattante.commerceflow.identity.Actor;
import dev.lattante.commerceflow.inventory.InventoryReservation.Demand;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

  OrderController(OrderService orders) {
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
