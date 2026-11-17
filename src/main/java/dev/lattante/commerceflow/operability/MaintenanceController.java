package dev.lattante.commerceflow.operability;

import dev.lattante.commerceflow.identity.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name = "bearerAuth")
class MaintenanceController {
  private final TokenMaintenance tokens;

  MaintenanceController(TokenMaintenance tokens) {
    this.tokens = tokens;
  }

  @GetMapping("/api/admin/operations/maintenance")
  TokenMaintenance.Snapshot snapshot(@AuthenticationPrincipal Actor actor) {
    return tokens.snapshot(actor);
  }
}
