package dev.lattante.commerceflow.identity;

public record Actor(String id, String email, AppUser.Role role) {}
