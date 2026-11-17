package dev.lattante.commerceflow.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@SecurityRequirement(name = "bearerAuth")
class UserAdministrationController {
  private final UserAdministration users;

  UserAdministrationController(UserAdministration users) {
    this.users = users;
  }

  record ChangeUser(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull AppUser.Role role,
      @NotNull Boolean active,
      @NotBlank @Size(max = 160) String reason) {}

  @GetMapping
  UserAdministration.UserPage list(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(required = false) AppUser.Role role,
      @RequestParam(required = false) Boolean active,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return users.list(actor, role, active, page, size);
  }

  @GetMapping("/{id}")
  UserAdministration.UserView find(@PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
    return users.find(id.toString(), actor);
  }

  @PutMapping("/{id}")
  @Operation(
      summary = "Revise user role and active state using the current version",
      description =
          "Actual changes revoke all target tokens and require a fresh login. "
              + "The last active administrator cannot be demoted or disabled. "
              + "409 requires re-reading and reconciling, never a blind version replacement.")
  UserAdministration.UserView update(
      @PathVariable UUID id,
      @AuthenticationPrincipal Actor actor,
      @Valid @RequestBody ChangeUser input) {
    return users.update(
        id.toString(),
        actor,
        new UserStateChange(input.expectedVersion(), input.role(), input.active(), input.reason()));
  }

  @GetMapping("/{id}/audit")
  UserAdministration.AuditPage audit(
      @PathVariable UUID id,
      @AuthenticationPrincipal Actor actor,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return users.audit(id.toString(), actor, page, size);
  }
}
