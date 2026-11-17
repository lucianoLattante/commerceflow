package dev.lattante.commerceflow.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.lattante.commerceflow.shared.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

class UserAdministrationTest {
  private final UserRepository users = mock(UserRepository.class);
  private final TokenRepository tokens = mock(TokenRepository.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AppUser operator =
      new AppUser("admin@example.test", "unused-synthetic-hash", AppUser.Role.ADMIN, Instant.EPOCH);
  private final Actor admin = new Actor(operator.getId(), operator.getEmail(), operator.getRole());
  private final UserAdministration service =
      new UserAdministration(users, tokens, jdbc, Clock.systemUTC());

  private UserStateChange change(AppUser.Role role, boolean active) {
    return new UserStateChange(0, role, active, "Access review");
  }

  private void guard() {
    when(users.lockAdministration()).thenReturn(Optional.of(1));
    when(users.findById(admin.id())).thenReturn(Optional.of(operator));
  }

  @Test
  void unauthorizedCallersCannotReachPersistenceThroughAnyServiceOperation() {
    var customer = new Actor("customer", "customer@example.test", AppUser.Role.CUSTOMER);
    for (Actor actor : Arrays.asList(null, customer)) {
      assertThrows(AccessDeniedException.class, () -> service.list(actor, null, null, 0, 20));
      assertThrows(AccessDeniedException.class, () -> service.find("missing", actor));
      assertThrows(AccessDeniedException.class, () -> service.audit("missing", actor, 0, 20));
      assertThrows(
          AccessDeniedException.class,
          () -> service.update("missing", actor, change(AppUser.Role.ADMIN, true)));
    }
    verifyNoInteractions(users, tokens, jdbc);
  }

  @Test
  void membershipGuardIsMandatoryAndMissingGuardFailsClosed() {
    when(users.lockAdministration()).thenReturn(Optional.empty());
    assertThrows(
        IllegalStateException.class,
        () -> service.update("missing", admin, change(AppUser.Role.CUSTOMER, false)));
    verify(users, never()).findById(anyString());
    verifyNoInteractions(tokens, jdbc);
  }

  @Test
  void previouslyAuthenticatedButNowDemotedOperatorIsRejectedAfterGuard() {
    guard();
    operator.changeState(change(AppUser.Role.CUSTOMER, true));
    assertThrows(
        AccessDeniedException.class,
        () -> service.update("missing", admin, change(AppUser.Role.CUSTOMER, false)));
    verify(users, never()).lockById(anyString());
    verifyNoInteractions(tokens, jdbc);
  }

  @Test
  void previouslyAuthenticatedButNowDisabledOperatorIsRejectedAfterGuard() {
    guard();
    operator.changeState(change(AppUser.Role.ADMIN, false));
    assertThrows(
        AccessDeniedException.class,
        () -> service.update("missing", admin, change(AppUser.Role.CUSTOMER, false)));
    verify(users, never()).lockById(anyString());
    verifyNoInteractions(tokens, jdbc);
  }

  @Test
  void lastActiveAdministratorCannotBeRemoved() {
    guard();
    when(users.lockById(admin.id())).thenReturn(Optional.of(operator));
    when(users.countByRoleAndActiveTrue(AppUser.Role.ADMIN)).thenReturn(1L);
    assertThrows(
        UserStateConflict.class,
        () -> service.update(admin.id(), admin, change(AppUser.Role.CUSTOMER, true)));
    assertThrows(
        UserStateConflict.class,
        () -> service.update(admin.id(), admin, change(AppUser.Role.ADMIN, false)));
    assertTrue(operator.isActive());
    assertEquals(AppUser.Role.ADMIN, operator.getRole());
    verify(users, never()).flush();
    verifyNoInteractions(tokens, jdbc);
  }

  @Test
  void noOpDoesNotRevokeTokensOrWriteAudit() {
    guard();
    when(users.lockById(admin.id())).thenReturn(Optional.of(operator));
    assertEquals(0, service.update(admin.id(), admin, change(AppUser.Role.ADMIN, true)).version());
    verify(users, never()).flush();
    verifyNoInteractions(tokens, jdbc);
  }

  @Test
  void staleVersionCannotChangeStateOrRevokeTokens() {
    guard();
    when(users.lockById(admin.id())).thenReturn(Optional.of(operator));
    assertThrows(
        UserStateConflict.class,
        () ->
            service.update(
                admin.id(),
                admin,
                new UserStateChange(1, AppUser.Role.CUSTOMER, false, "Old administrative read")));
    assertEquals(AppUser.Role.ADMIN, operator.getRole());
    assertTrue(operator.isActive());
    verify(users, never()).flush();
    verifyNoInteractions(tokens, jdbc);
  }

  @Test
  void domainRejectsInvalidCommandAndStaleEntityMutation() {
    assertThrows(
        DomainViolation.class, () -> new UserStateChange(-1, AppUser.Role.ADMIN, true, "Review"));
    assertThrows(DomainViolation.class, () -> new UserStateChange(0, null, true, "Review"));
    assertThrows(
        DomainViolation.class, () -> new UserStateChange(0, AppUser.Role.ADMIN, true, " "));
    assertThrows(
        DomainViolation.class,
        () -> new UserStateChange(0, AppUser.Role.ADMIN, true, "x".repeat(161)));
    assertThrows(
        UserStateConflict.class,
        () ->
            operator.changeState(
                new UserStateChange(1, AppUser.Role.CUSTOMER, false, "Stale version")));
    assertEquals(AppUser.Role.ADMIN, operator.getRole());
    assertTrue(operator.isActive());
  }

  @Test
  void paginationIsBoundedBeforeDatabaseReads() {
    assertThrows(DomainViolation.class, () -> service.list(admin, null, null, -1, 20));
    assertThrows(DomainViolation.class, () -> service.list(admin, null, null, 0, 101));
    assertThrows(DomainViolation.class, () -> service.audit("missing", admin, 10001, 20));
    verifyNoInteractions(users, tokens, jdbc);
  }
}
