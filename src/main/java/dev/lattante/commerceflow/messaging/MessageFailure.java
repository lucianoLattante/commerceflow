package dev.lattante.commerceflow.messaging;

public class MessageFailure extends RuntimeException {
  public MessageFailure() { super("Message cannot be applied"); }
  public static class OutOfOrder extends RuntimeException {}
  public static class Conflict extends RuntimeException {}
}
