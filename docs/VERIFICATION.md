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
