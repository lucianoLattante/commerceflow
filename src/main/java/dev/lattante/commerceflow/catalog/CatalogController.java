package dev.lattante.commerceflow.catalog;

import dev.lattante.commerceflow.shared.Money;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
class CatalogController {
  private final CatalogService catalog;

  CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  record CreateProduct(
      @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9._-]{0,63}") String sku,
      @NotBlank @Size(max = 160) String name,
      @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
      @NotNull Money.Currency currency,
      @Min(0) @Max(1000000) int stock) {}

  @PostMapping
  @SecurityRequirement(name = "bearerAuth")
  ResponseEntity<CatalogService.ProductView> create(@Valid @RequestBody CreateProduct input) {
    var created =
        catalog.create(input.sku(), input.name(), input.amount(), input.currency(), input.stock());
    return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
  }

  @GetMapping("/{id}")
  CatalogService.ProductView find(@PathVariable String id) {
    return catalog.find(id);
  }

  @GetMapping
  CatalogService.PageView<CatalogService.ProductView> list(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return catalog.list(q, page, size);
  }
}
