package dev.lattante.commerceflow.messaging;

import dev.lattante.commerceflow.shared.DomainViolation;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

final class MessageDigests {
  static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  static String key(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{8,128}"))
      throw new DomainViolation("Idempotency-Key needs 8..128 ASCII letters/digits or . _ : -");
    return hash(value);
  }
}
