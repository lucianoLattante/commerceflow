package dev.lattante.commerceflow.identity;

import dev.lattante.commerceflow.shared.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class UserAdministration {
  public record UserView(
      String id,
      String email,
      AppUser.Role role,
      boolean active,
      long version,
      Instant createdAt) {}

  public record UserPage(List<UserView> content, int page, int size, long totalElements) {}

  public record State(AppUser.Role role, boolean active, long version) {}

  public record AuditEntry(
      String id,
      String actorId,
      String reason,
      Instant occurredAt,
      State before,
      State after,
      int revokedTokens) {}

  public record AuditPage(List<AuditEntry> content, int page, int size, long totalElements) {}

  private final UserRepository users;
  private final TokenRepository tokens;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public UserAdministration(
      UserRepository users, TokenRepository tokens, JdbcTemplate jdbc, Clock clock) {
    this.users = users;
    this.tokens = tokens;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public UserView find(String id, Actor actor) {
    admin(actor);
    return view(users.findById(id).orElseThrow(NotFound::new));
  }

  @Transactional(readOnly = true)
  public UserPage list(Actor actor, AppUser.Role role, Boolean active, int page, int size) {
    admin(actor);
    pagination(page, size);
    Specification<AppUser> filter =
        (root, q, cb) ->
            cb.and(
                role == null ? cb.conjunction() : cb.equal(root.get("role"), role),
                active == null ? cb.conjunction() : cb.equal(root.get("active"), active));
    var result = users.findAll(filter, PageRequest.of(page, size, Sort.by("createdAt", "id")));
    return new UserPage(
        result.map(UserAdministration::view).getContent(), page, size, result.getTotalElements());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public UserView update(String id, Actor actor, UserStateChange change) {
    admin(actor);
    Objects.requireNonNull(change);
    // Membership changes (including bootstrap) always acquire this guard before any user row.
    users
        .lockAdministration()
        .orElseThrow(() -> new IllegalStateException("Identity guard missing"));
    AppUser operator =
        users
            .findById(actor.id())
            .orElseThrow(() -> new AccessDeniedException("Administrator required"));
    if (!operator.isActive() || operator.getRole() != AppUser.Role.ADMIN)
      throw new AccessDeniedException("Administrator required");
    // Login holds this same target row until its token issuance transaction commits.
    AppUser target = users.lockById(id).orElseThrow(NotFound::new);
    if (target.getVersion() != change.expectedVersion())
      throw new UserStateConflict("User changed; read the current version and reconcile");
    if (target.getRole() == change.role() && target.isActive() == change.active())
      return view(target);
    if (target.isActive()
        && target.getRole() == AppUser.Role.ADMIN
        && (!change.active() || change.role() != AppUser.Role.ADMIN)
        && users.countByRoleAndActiveTrue(AppUser.Role.ADMIN) <= 1)
      throw new UserStateConflict("At least one active administrator must remain");

    State before = state(target);
    target.changeState(change);
    users.flush();
    int revoked = tokens.deleteForUser(id);
    State after = state(target);
    jdbc.update(
        """
        INSERT INTO user_admin_audit(id,user_id,actor_id,user_version,reason,occurred_at,
          before_role,before_active,before_version,after_role,after_active,revoked_tokens)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID().toString(),
        id,
        actor.id(),
        after.version(),
        change.reason(),
        Timestamp.from(clock.instant()),
        before.role().name(),
        before.active(),
        before.version(),
        after.role().name(),
        after.active(),
        revoked);
    return view(target);
  }

  @Transactional(readOnly = true)
  public AuditPage audit(String id, Actor actor, int page, int size) {
    admin(actor);
    pagination(page, size);
    if (!users.existsById(id)) throw new NotFound();
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_admin_audit WHERE user_id=?", Long.class, id);
    var rows =
        jdbc.query(
            """
            SELECT id,actor_id,reason,occurred_at,before_role,before_active,before_version,
              after_role,after_active,user_version,revoked_tokens
            FROM user_admin_audit WHERE user_id=? ORDER BY user_version DESC LIMIT ? OFFSET ?
            """,
            (rs, n) ->
                new AuditEntry(
                    rs.getString("id"),
                    rs.getString("actor_id"),
                    rs.getString("reason"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    new State(
                        AppUser.Role.valueOf(rs.getString("before_role")),
                        rs.getBoolean("before_active"),
                        rs.getLong("before_version")),
                    new State(
                        AppUser.Role.valueOf(rs.getString("after_role")),
                        rs.getBoolean("after_active"),
                        rs.getLong("user_version")),
                    rs.getInt("revoked_tokens")),
            id,
            size,
            page * size);
    return new AuditPage(rows, page, size, total);
  }

  private static State state(AppUser user) {
    return new State(user.getRole(), user.isActive(), user.getVersion());
  }

  private static UserView view(AppUser user) {
    return new UserView(
        user.getId(),
        user.getEmail(),
        user.getRole(),
        user.isActive(),
        user.getVersion(),
        user.getCreatedAt());
  }

  private static void admin(Actor actor) {
    if (actor == null || actor.role() != AppUser.Role.ADMIN)
      throw new AccessDeniedException("Administrator required");
  }

  private static void pagination(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new DomainViolation("Page must be 0..10000 and size 1..100");
  }
}
