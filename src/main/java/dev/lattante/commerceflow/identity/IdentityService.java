package dev.lattante.commerceflow.identity;

import dev.lattante.commerceflow.shared.DomainViolation;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
  private final UserRepository users;
  private final TokenRepository tokens;
  private final PasswordEncoder passwords;
  private final Clock clock;
  private final Duration lifetime;
  private final SecureRandom random = new SecureRandom();
  private final String dummyHash;

  public IdentityService(
      UserRepository users,
      TokenRepository tokens,
      PasswordEncoder passwords,
      Clock clock,
      @Value("${commerceflow.token-lifetime:PT15M}") Duration lifetime) {
    if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofHours(1)) > 0)
      throw new IllegalArgumentException("Token lifetime must be between zero and one hour");
    this.users = users;
    this.tokens = tokens;
    this.passwords = passwords;
    this.clock = clock;
    this.lifetime = lifetime;
    dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  public record UserView(String id, String email, AppUser.Role role) {}

  public record IssuedToken(String accessToken, String tokenType, Instant expiresAt) {}

  @Transactional
  public UserView register(String email, String password) {
    return view(
        users.saveAndFlush(
            new AppUser(
                normalize(email), encode(password), AppUser.Role.CUSTOMER, clock.instant())));
  }

  @Transactional
  public IssuedToken login(String email, String password) {
    if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new DomainViolation("Invalid password length");
    AppUser user = users.findByEmail(normalize(email)).orElse(null);
    boolean valid = passwords.matches(password, user == null ? dummyHash : user.getPasswordHash());
    if (!valid || user == null || !user.isActive()) throw new Unauthorized();
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = "cf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expiry = clock.instant().plus(lifetime);
    tokens.save(new AccessToken(digest(token), user, expiry));
    return new IssuedToken(token, "Bearer", expiry);
  }

  @Transactional(readOnly = true)
  public Optional<Actor> authenticate(String raw) {
    if (raw == null || !raw.matches("cf_[A-Za-z0-9_-]{43}")) return Optional.empty();
    return tokens
        .findByDigest(digest(raw))
        .filter(t -> t.expiresAt.isAfter(clock.instant()) && t.user.isActive())
        .map(t -> new Actor(t.user.getId(), t.user.getEmail(), t.user.getRole()));
  }

  @Transactional
  public void revoke(String raw) {
    tokens.deleteById(digest(raw));
  }

  @Transactional(readOnly = true)
  public UserView me(Actor actor) {
    return view(users.findById(actor.id()).orElseThrow(Unauthorized::new));
  }

  @Transactional
  public void bootstrap(String email, String password) {
    String normalized = normalize(email);
    users
        .findByEmail(normalized)
        .ifPresentOrElse(
            existing -> {
              if (existing.getRole() != AppUser.Role.ADMIN)
                throw new IllegalStateException("Bootstrap account is not an administrator");
            },
            () ->
                users.save(
                    new AppUser(
                        normalized, encode(password), AppUser.Role.ADMIN, clock.instant())));
  }

  private String encode(String raw) {
    if (raw == null || raw.length() < 12 || raw.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new DomainViolation(
          "Password must have at least 12 characters and at most 72 UTF-8 bytes");
    return passwords.encode(raw);
  }

  private static String normalize(String email) {
    if (email == null) throw new DomainViolation("Email is required");
    return email.strip().toLowerCase(Locale.ROOT);
  }

  private static UserView view(AppUser user) {
    return new UserView(user.getId(), user.getEmail(), user.getRole());
  }

  static String digest(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
