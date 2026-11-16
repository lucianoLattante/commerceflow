package dev.lattante.commerceflow.orders;

import dev.lattante.commerceflow.inventory.InventoryReservation.Demand;
import dev.lattante.commerceflow.shared.DomainViolation;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public record OrderSubmission(List<Demand> items) {
  public OrderSubmission {
    if (items == null || items.isEmpty() || items.size() > 50)
      throw new DomainViolation("Orders need 1 to 50 distinct items");
    Set<UUID> ids = new HashSet<>();
    for (Demand item : items) {
      if (item == null || item.productId() == null || item.quantity() < 1 || item.quantity() > 1000)
        throw new DomainViolation("Each item needs a product and quantity 1 to 1000");
      if (!ids.add(item.productId()))
        throw new DomainViolation("Duplicate products are not allowed");
    }
    items = items.stream().sorted(Comparator.comparing(i -> i.productId().toString())).toList();
  }

  public String fingerprint() {
    StringBuilder canonical = new StringBuilder("commerceflow.order.v1\n");
    for (Demand item : items)
      canonical.append(item.productId()).append(':').append(item.quantity()).append('\n');
    return hash(canonical.toString());
  }

  static String keyHash(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}"))
      throw new DomainViolation(
          "Idempotency-Key must contain 8 to 128 ASCII letters, digits, dots, colons, underscores"
              + " or hyphens");
    return hash(key);
  }

  private static String hash(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
