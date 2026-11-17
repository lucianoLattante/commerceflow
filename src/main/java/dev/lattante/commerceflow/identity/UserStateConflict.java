package dev.lattante.commerceflow.identity;

public class UserStateConflict extends RuntimeException {
  UserStateConflict(String message) {
    super(message);
  }
}
