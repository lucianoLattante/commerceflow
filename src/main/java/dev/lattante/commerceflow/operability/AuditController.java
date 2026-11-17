package dev.lattante.commerceflow.operability;

import dev.lattante.commerceflow.identity.Actor;
import dev.lattante.commerceflow.orders.OrderService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name = "bearerAuth")
class AuditController {
  private final OrderService orders;
  private final OrderOperationAudit audit;

  AuditController(OrderService orders, OrderOperationAudit audit) {
    this.orders = orders;
    this.audit = audit;
  }

  @GetMapping("/api/orders/{id}/audit")
  List<OrderOperationAudit.Entry> owned(
      @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
    orders.find(id.toString(), actor);
    return audit.forOrder(id.toString());
  }

  @GetMapping("/api/admin/operations/audit")
  OrderOperationAudit.AuditPage list(
      @RequestParam(required = false) OrderOperationAudit.Action action,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return audit.list(action, page, size);
  }
}
