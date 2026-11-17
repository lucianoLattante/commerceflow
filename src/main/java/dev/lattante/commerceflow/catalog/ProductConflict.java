package dev.lattante.commerceflow.catalog;

public class ProductConflict extends RuntimeException {
  ProductConflict(String message) {
    super(message);
  }
}
