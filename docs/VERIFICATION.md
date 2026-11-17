# Verification checkpoint — 2026-11-16

Runtime-source commit: `3212e9425c51c658e11efc6f79fdd21a5787ff1d`.

[CI run 43912826951](https://github.com/lucianoLattante/commerceflow/actions/runs/43912826951), job 133458997795, completed successfully:

- 33 unit tests and 24 integration tests; zero failures, errors or skipped tests.
- Real disposable MySQL 8.4.11: Flyway migration, Hibernate validation, HTTP identity/catalog behavior, constraints and token lifecycle.
- Compose configuration validation.
- Docker image build and actual application startup.
- HTTP health returned UP; the public catalog endpoint responded successfully.
- The unused default Spring Security account is excluded.

Commands: `mvn -B --no-transfer-progress verify`, `docker compose config --quiet`, `docker compose up -d --build --wait --wait-timeout 180`, then HTTP health/catalog checks. Containers and disposable volumes were removed by CI. This was not a public deployment.

Local: JDK 21.0.7, Maven 3.9.0; build and 33 unit tests passed. The local Docker daemon/Compose plugin was unavailable. Standalone Compose 5.7.1, checked against the official GitHub-release SHA-256, validated the configuration. The environment script generated independent secrets and refused to overwrite an existing file.

Earlier failed runs are preserved: the first exposed an invalid SQL column type in the unreleased migration; the second exposed two test assumptions about observability setup and MySQL's constraint exception classification. They were corrected before this successful checkpoint. They are not counted as passing tests.

Limits: no order-processing, persistent concurrent reservation, idempotency/outbox, payment, Kafka, Redis, load benchmark, complete dependency advisory audit or deployment has been verified in this increment. Those remain roadmap work. See [architecture](ARCHITECTURE.md) and [roadmap](../ROADMAP.md).

## Transactional orders checkpoint — 2026-11-16

Runtime-source commit b91b4cbf1df9b58856f28de266fb4c1325974186, [CI 43932466541](https://github.com/lucianoLattante/commerceflow/actions/runs/43932466541), job 133528220109: success. 54 unit and 55 MySQL integration tests passed, zero failures/errors/skips. Includes 30 order scenarios, 24 foundation scenarios and V1-to-V2 preservation. Concurrent same-key/limited-stock/opposite-order tests and rollback after an outbox SQL failure passed.

Docker Compose built and started the actual application; readiness, catalog and scripts/smoke-orders.py passed over network HTTP. Timestamps are canonicalized to TIMESTAMP(6) precision. The trigger-permission setting is test-container-only; runtime Compose was not relaxed.

Earlier runs exposed and corrected timestamp precision, disposable trigger setup and a lowercase smoke SKU. Payment/cancel/expiry and Kafka publication remain pending.

## CF-04 lifecycle checkpoint — 2026-11-16 (Argentina)

Runtime-source commit: `3bbf48617ec2d49629cf1a2eeb9fda6709655c0e`.
[CI run 43985895558](https://github.com/lucianoLattante/commerceflow/actions/runs/43985895558),
job 133713821358, completed successfully. GitHub log timestamps are UTC on 2026-11-17.

- **65 unit tests**: zero failures, errors or skipped tests.
- **78 MySQL integration tests**: 53 OrderFlowIT, 24 CommerceFlowIT and 1 SchemaUpgradeIT;
  zero failures, errors or skipped tests.
- Exact-deadline and immutable terminal guards; digest-only payment replay/conflict.
- Owner-only mutation, anonymous rejection and OpenAPI bearer requirements.
- Approval/decline/cancel/expiry outbox-failure rollback and successful retry.
- Rollback of an earlier inventory-line consumption when a later persisted reservation is inconsistent.
- Concurrent payment retries; payment/cancel races; due payment/expiry races; shared-product transitions.
- Real MySQL candidate selection with 102 due orders, limit 100, stable ordering and terminal/future exclusions.
- Populated V1 identity/catalog and V2 reservation preserved through V3, including its 900 second backfilled lease.
- Compose configuration, image build, actual application startup and HTTP readiness/catalog.
- Network HTTP smoke: reservation/replay, approval/replay, cancellation/replay, decline and rejected payment of a cancelled order.

Commands executed by the workflow:

```bash
mvn -B --no-transfer-progress verify
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
curl --fail --silent --retry 30 --retry-all-errors --retry-delay 2 --max-time 5 http://localhost:8080/actuator/health/readiness
curl --fail --silent http://localhost:8080/api/products
python3 scripts/smoke-orders.py
docker compose down --volumes
```

Runtime evidence: GitHub-hosted Ubuntu 24.04, Temurin 21.0.13+9, MySQL 8.4.11,
Testcontainers 2.0.5 and Docker server 28.0.4. The existing workflow does not print its host
Maven version; it is runner-provided. The application Docker builder is maven:3.9.11-eclipse-temurin-21.
No new local compilation/test execution is claimed: the workstation environment became unavailable.
The partially prepared local folder must be reconciled with the reviewed remote source after reconnection,
preserving any unrelated edits.

Runs 43985000440 and 43985474505 failed one assertion expecting a narrower Spring exception subtype.
MySQL correctly rejected an illegal PAID row with error 3819 and ck_order_payment.
The assertion now checks the SQL error code/constraint; these runs are not counted as successful.
Run 43985639235 passed before the final refinement that classifies persisted reservation drift as
an internal error. Run 43985895558 verifies that refinement too.

Documentation-only commits after the runtime-source commit record these results; their source,
tests, migrations, workflow, scripts and infrastructure must match the verified runtime tree.
The PR records the final integration state.

Limits: scheduler wall-clock timing is not tested through a sleeping end-to-end test.
Batch failure isolation is unit tested; query/expiry transactions use real MySQL; Compose starts
with scheduling enabled. An oldest batch of persistently failing reservations could delay later
candidates and needs future operator tooling. Flyway emits a MySQL-version support warning;
successful migrations here are functional evidence, not vendor certification.
No real payment, refund, Kafka delivery, Redis, complete advisory audit, benchmark or public deployment is claimed.

## CF-05 messaging checkpoint — 2026-11-16 (Argentina)

Runtime source: `bc5ba33a59bf4a27c26b160313b2c79a0acd17dc`.
[CI run 43992169237](https://github.com/lucianoLattante/commerceflow/actions/runs/43992169237),
job 133735252539, completed successfully. Log timestamps are UTC on 2026-11-17.

**82 unit + 95 integration tests**, zero failures/errors/skipped.
Integration split: 54 OrderFlowIT, 24 CommerceFlowIT, 1 SchemaUpgradeIT and 16 KafkaDeliveryIT.

Executed evidence includes:
- Atomic transport enqueue rollback with business outbox/order/stock.
- Actual broker acknowledgement followed by injected DB mark failure; two identical broker records
  but one inbox/projection effect after lease recovery.
- Expired-token fencing, abandoned fifth-lease failure and actual unavailable-broker retry exhaustion.
- Concurrent publisher polling under SKIP LOCKED and concurrent duplicate consumers.
- Consumer projection rollback, three attempts, durable recovery, repair and exact-payload replay.
- DLQ capture/transport rollback retaining the original offset, then successful recovery/offset advance.
- Poison/changed/out-of-order events, source sequence gates, metadata authorization and recovery budgets.
- V4 backfill after populated V1/V2, preserving identity/catalog/reservation/business-event data.
- Protocol version validation rejecting string/fractional coercion.
- Compose config/image build/startup/readiness/catalog and network HTTP lifecycle plus Kafka projections.

Runtime: Ubuntu 24.04, Temurin 21.0.13+9, Maven 3.10.0, Docker server 28.0.4,
Compose v2.38.2, MySQL 8.4.11, Apache Kafka broker 4.1.1, Spring Kafka 3.3.16,
Kafka client 3.9.2 and Testcontainers 2.0.5. The Docker builder uses Maven 3.9.11/Java 21.
No local execution is claimed while the executor is unavailable.

```bash
java -version
mvn -version
docker version
docker compose version
mvn -B --no-transfer-progress verify
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 240
curl --fail --silent --retry 30 --retry-all-errors --retry-delay 2 --max-time 5 http://localhost:8080/actuator/health/readiness
curl --fail --silent http://localhost:8080/api/products
python3 scripts/smoke-orders.py
docker compose down --volumes
```

Failed attempts remain visible: 43990652562 exposed cross-test Kafka records after DB cleanup;
43990846934 and 43991702312 exposed an incorrect assumption that each concurrent SKIP LOCKED poll
must claim work immediately. Test isolation now resets only the inactive test group between cases,
and the competing publisher test uses bounded polling. Recovery scenarios never reset offsets.
The production consumer never resets offsets. These failures are not counted as successful.

Run 43991841530 passed 78 unit + 95 integration tests and Compose/HTTP before the final strict
protocol-version validation. Run 43992169237 verifies that refinement with four additional unit cases.
Documentation-only commits after this runtime source must preserve all source/tests/migrations,
workflow, scripts and infrastructure byte-for-byte; the PR records integration state.

Limits: one local PLAINTEXT broker with replication factor 1; no production ACL/TLS, fleet rebalance
certification, full advisory audit, retention policy, load benchmark or public deployment.
Flyway's existing MySQL-version support warning remains. Functional migrations are demonstrated;
vendor certification is not claimed. The CF-04 scheduler-timing and clock limitations still apply.

## CF-06 admission/audit/metrics checkpoint — 2026-11-17

Runtime source: `9e62dd4ef5a22eb4ae2f64bda01c957052a2f663`.
[CI run 44083936277](https://github.com/lucianoLattante/commerceflow/actions/runs/44083936277),
job 134041304697: success. **90 unit + 108 integration tests**, zero failures/errors/skipped.

Integration split: 58 OrderFlowIT, 24 CommerceFlowIT, 1 SchemaUpgradeIT, 16 KafkaDeliveryIT,
9 RedisGuardIT. These execute actual disposable MySQL 8.4.11, Kafka 4.1.1 and Redis 8.2.11.

Verified cases include concurrent Redis limiter instances, TTL expiry and exhausted-counter TTL repair,
forged forwarding headers, policy/actor isolation, authorization before admission, and logout exemption.
Docker pause/unpause verifies fail-closed HTTP 503 without business writes, readable metrics/public
catalog, readiness degradation, then recovery using the same unconsumed order idempotency key.
Audit failure and a later outbox failure verify rollback and absence of after-commit counter increments.
Payment retries and expiry no-ops produce one audit/counter per transition. Prior Kafka races,
broker-ack crash-window, DLQ/replay and migration preservation tests continue to pass.

Compose config, image build/startup/readiness/catalog, order lifecycle, Kafka projection and new
audit/admission network HTTP smoke all passed. No public deployment was performed.

CI toolchain: Ubuntu 24.04, Temurin 21.0.13+9, Maven 3.10.0, Docker server 28.0.4,
Compose v2.38.2, Testcontainers 2.0.5. Spring Boot continues to manage the Redis/Lettuce dependencies;
no override was introduced. Redis image is fixed at 8.2.11.

```bash
java -version
mvn -version
docker version
docker compose version
mvn -B --no-transfer-progress verify
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 240
curl --fail --silent --retry 30 --retry-all-errors --retry-delay 2 --max-time 5 http://localhost:8080/actuator/health/readiness
curl --fail --silent http://localhost:8080/api/products
python3 scripts/smoke-orders.py
docker compose down --volumes
```

Local Windows: Oracle JDK 21.0.7 and Maven 3.9.0. Maven was launched through its Plexus Classworlds
Java entry point with `-B --no-transfer-progress -Dmaven.repo.local=C:/workspace/maven-commerceflow test`.
90 unit tests passed twice (initial source and TTL-repair refinement); integration classes compiled.
No local integration execution is claimed because the Docker daemon was unavailable.
Standalone Compose 5.7.1 validated configuration without starting services.
The exact launcher arguments are retained in the private working verification record.

PowerShell checks executed init-env.ps1 and add-redis-env.ps1 in an isolated generated fixture folder.
They created four independent random secrets, refused overwrites and preserved prior bytes/settings;
values were never printed. These script checks are not counted as JUnit tests.

Initial CI 44083159190 passed 90 unit + 107 integration tests and Compose/HTTP before the additional
TTL-repair case. No failed CI result was encountered in this increment. Final source above verifies
the repair and 108 integration cases. Documentation-only commits after it must preserve all
runtime/test/migration/workflow/script/infrastructure blobs unchanged.

The previous local source folder contained partial older edits and was left unchanged.
A new plain source working copy was materialized from the real upstream commit; it is not presented
as a Git clone/history. The feature branch and PR use the upstream Git commit graph.
The shell Git transport remained blocked by DNS/thread startup, so the authorized GitHub connector
handled branch/PR operations. No new authentication was needed for that route.

Limits: admission defaults off for host builds and is enabled in Compose. Enabled Redis failure
denies protected requests, with no permissive fallback. Socket quotas need an explicit trusted-proxy
design before deployment; window boundary bursts, NAT sharing and Redis restart quota resets remain.
Order audit starts with V5 without fabricated history. Gauges are non-atomic observations and process
counters reset on restart. Redis TLS/ACL/HA, complete abuse defense, retention, complete identity/product
administration, full advisory audit, load capacity and public deployment remain unverified.
The existing Flyway MySQL-support warning and CF-04/05 clock/security limitations are not closed.

## Catalog administration — 2026-11-17

Verified runtime source: `d4fa34cb5cc04c5770b6b7462a2dbe37b7d1a675`.
[CI 44089250548](https://github.com/lucianoLattante/commerceflow/actions/runs/44089250548),
job 134059754197, completed successfully.

- 99 unit tests, zero failures/errors/skipped.
- 123 integration tests: OrderFlowIT 73, CommerceFlowIT 24, SchemaUpgradeIT 1,
  KafkaDeliveryIT 16 and RedisGuardIT 9; zero failures/errors/skipped.
- Compose configuration, image build/startup, readiness/public catalog, order lifecycle, Kafka
  projections, catalog administration, audit and Redis admission network HTTP smoke all passed.
- V1-V5 migration blobs are unchanged. The populated upgrade preserves stock/reservations/version
  and activates existing products with V6's default. No public deployment.

New MySQL cases exercised two competing administrators, reservation/deactivation and payment/stock
races, partial-basket rollback, old order snapshots/payment and cancellation of inactive reservations,
stale/no-op requests, transactional audit-insert failure and rollback of fields/version,
role boundaries, required DTO fields, ignored forged fields, discovery/pagination and audit order.

CI runtime: Ubuntu 24.04, Temurin 21.0.13+9, Maven 3.10.0, Docker server 28.0.4,
Compose v2.38.2. Existing Testcontainers 2.0.5 and dependencies were unchanged.
Exact workflow commands:

```bash
java -version
mvn -version
docker version
docker compose version
mvn -B --no-transfer-progress verify
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 240
curl --fail --silent --retry 30 --retry-all-errors --retry-delay 2 --max-time 5 http://localhost:8080/actuator/health/readiness
curl --fail --silent http://localhost:8080/api/products
python3 scripts/smoke-orders.py
docker compose down --volumes
```

Local Windows: Oracle JDK 21.0.7 / Maven 3.9.0. Two executions of 99 unit tests passed;
integration sources compiled, not executed locally because no Docker daemon was available.
Maven's Java/Plexus launcher used `-B --no-transfer-progress -Dmaven.repo.local=C:/workspace/maven-commerceflow test`;
its full launcher arguments are in the private verification record. The second run includes
the final payment/basket scenarios. Markdown local targets and Python AST validation passed;
these static checks are not runtime tests. No failed CI run occurred in this increment.

The previous source copies remain preserved. A fresh plain source copy was compared with all 116
upstream branch blobs; it is not represented as a local Git clone/history. Connector operations used
the real feature branch and commit ancestry. Before publication, a limited credential-pattern check
and manual review of changed source found no embedded live credentials; this is not a full advisory
or history scan.

Follow-up documentation commits must preserve every runtime/test/migration/script/workflow/
infrastructure blob from the verified source. PR #6 records integration separately from test success.

Limits: revision conflicts require manual re-read/reconciliation; no automatic merge or
idempotent-response replay. Product audit starts with V6 administrative edits, excludes creation
and routine order stock movements, and is append-only through the API rather than tamper-proof
against database operators. No warehouse ledger, user administration, retention, full advisory audit,
load capacity or production deployment was verified.
