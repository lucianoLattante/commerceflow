package dev.lattante.commerceflow.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.lattante.commerceflow.shared.DomainViolation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.*;

class TokenMaintenanceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final ExpiredTokenCleanup cleanup = new ExpiredTokenCleanup(jdbc, clock, metrics);

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
    metrics.close();
  }

  @Test
  void cleanupBoundsAreValidatedBeforeSql() {
    assertThatThrownBy(() -> cleanup.clean(0)).isInstanceOf(DomainViolation.class);
    assertThatThrownBy(() -> cleanup.clean(1001)).isInstanceOf(DomainViolation.class);
    verifyNoInteractions(jdbc);
  }

  @Test
  void emptyScanCountsOnlyAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();
    when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn(List.of());
    assertThat(cleanup.clean(200)).isZero();
    assertThat(metrics.find("commerceflow.identity.cleanup.runs").counter()).isNull();
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    assertThat(
            metrics
                .get("commerceflow.identity.cleanup.runs")
                .tag("outcome", "committed")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(metrics.get("commerceflow.identity.cleanup.deleted").counter().count()).isZero();
  }

  @Test
  void postCommitObservationFailureCannotTurnDeletionIntoAnApplicationFailure() {
    var broken = mock(MeterRegistry.class);
    when(broken.counter(anyString(), any(String[].class)))
        .thenThrow(new IllegalStateException("Synthetic observation fault"));
    TransactionSynchronizationManager.initSynchronization();
    when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
        .thenReturn(List.of("a".repeat(64)));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    assertThat(new ExpiredTokenCleanup(jdbc, clock, broken).clean(1)).isEqualTo(1);
    assertThatCode(
            () ->
                TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit))
        .doesNotThrowAnyException();
  }

  @Test
  void failedWorkerAttemptIsObservableAndDoesNotPreventNextTick() {
    var failing = mock(ExpiredTokenCleanup.class);
    when(failing.clean(200))
        .thenThrow(new IllegalStateException("Synthetic DB outage"))
        .thenReturn(0);
    var worker =
        new TokenCleanupWorker(failing, new TokenCleanupSettings(true, 200, 60000), metrics);
    worker.tick();
    worker.tick();
    verify(failing, times(2)).clean(200);
    assertThat(
            metrics
                .get("commerceflow.identity.cleanup.runs")
                .tag("outcome", "failed")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            metrics
                .find("commerceflow.identity.cleanup.runs")
                .tag("outcome", "committed")
                .counter())
        .isNull();
  }

  @Test
  void snapshotRejectsNonAdministratorsBeforeReadingMetadata() {
    var service = new TokenMaintenance(jdbc, clock, new TokenCleanupSettings(false, 200, 60000));
    for (Actor actor :
        Arrays.asList(null, new Actor("customer", "customer@example.test", AppUser.Role.CUSTOMER)))
      assertThatThrownBy(() -> service.snapshot(actor)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(jdbc);
  }

  @Test
  void maintenanceDefaultsAreDisabledAndBounded() {
    new ApplicationContextRunner()
        .withUserConfiguration(IdentityMaintenanceConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var settings = context.getBean(TokenCleanupSettings.class);
              assertThat(settings.enabled()).isFalse();
              assertThat(settings.batchSize()).isEqualTo(200);
              assertThat(settings.intervalMs()).isEqualTo(60000);
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"batch-size=0", "batch-size=1001", "interval-ms=999", "interval-ms=3600001"})
  void invalidMaintenanceConfigurationPreventsStartup(String setting) {
    new ApplicationContextRunner()
        .withUserConfiguration(IdentityMaintenanceConfiguration.class)
        .withPropertyValues("commerceflow.identity.cleanup." + setting)
        .run(context -> assertThat(context).hasFailed());
  }
}
