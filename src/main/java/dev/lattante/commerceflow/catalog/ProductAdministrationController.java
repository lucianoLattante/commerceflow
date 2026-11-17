package dev.lattante.commerceflow.catalog;

import dev.lattante.commerceflow.identity.Actor;
import dev.lattante.commerceflow.shared.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/catalog/products")
@SecurityRequirement(name = "bearerAuth")
class ProductAdministrationController {
  private final ProductAdministration products;

  ProductAdministrationController(ProductAdministration products) {
    this.products = products;
  }

  record UpdateProduct(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 160) String name,
      @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
      @NotNull Money.Currency currency,
      @NotNull @Min(0) @Max(1000000) Integer onHand,
      @NotNull Boolean active,
      @NotBlank @Size(max = 160) String reason) {}

  @GetMapping
  CatalogService.PageView<ProductAdministration.ProductView> list(
      @AuthenticationPrincipal Actor actor,
      @RequestParam(required = false) Boolean active,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return products.list(actor, active, page, size);
  }

  @GetMapping("/{id}")
  ProductAdministration.ProductView find(
      @PathVariable UUID id, @AuthenticationPrincipal Actor actor) {
    return products.find(id.toString(), actor);
  }

  @PutMapping("/{id}")
  @Operation(
      summary = "Replace mutable product state using the current version",
      description =
          "409 requires re-reading and reconciling; never blindly retry with a new version. onHand"
              + " is an absolute balance including reserved units. SKU and reservations are"
              + " immutable here.")
  ProductAdministration.ProductView update(
      @PathVariable UUID id,
      @AuthenticationPrincipal Actor actor,
      @Valid @RequestBody UpdateProduct input) {
    return products.update(
        id.toString(),
        actor,
        new ProductEdit(
            input.expectedVersion(),
            input.name(),
            input.amount(),
            input.currency(),
            input.onHand(),
            input.active(),
            input.reason()));
  }

  @GetMapping("/{id}/audit")
  ProductAdministration.AuditPage audit(
      @PathVariable UUID id,
      @AuthenticationPrincipal Actor actor,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return products.audit(id.toString(), actor, page, size);
  }
}
