package dev.lattante.commerceflow.identity;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class IdentityController {
  private final IdentityService identity;

  IdentityController(IdentityService identity) {
    this.identity = identity;
  }

  record Credentials(
      @NotBlank @Email @Size(max = 254) String email,
      @NotNull @Size(min = 12, max = 72) String password) {}

  @PostMapping("/auth/register")
  @ResponseStatus(HttpStatus.CREATED)
  IdentityService.UserView register(@Valid @RequestBody Credentials input) {
    return identity.register(input.email(), input.password());
  }

  @PostMapping("/auth/token")
  IdentityService.IssuedToken token(@Valid @RequestBody Credentials input) {
    return identity.login(input.email(), input.password());
  }

  @PostMapping("/auth/logout")
  @SecurityRequirement(name = "bearerAuth")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(@RequestHeader("Authorization") String authorization) {
    identity.revoke(authorization.substring(7));
  }

  @GetMapping("/users/me")
  @SecurityRequirement(name = "bearerAuth")
  IdentityService.UserView me(@AuthenticationPrincipal Actor actor) {
    return identity.me(actor);
  }
}
