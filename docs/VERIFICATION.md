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

## CF-04 verification in progress

Feature branch: feat/order-lifecycle, PR #3. The prior order-creation counts above remain historical.
The PC execution environment became unavailable during this increment; no new local tests are claimed.
GitHub Actions runs Maven verify on Java 21 / Ubuntu 24.04 with disposable MySQL 8.4.11, then
Compose configuration/build/readiness checks and the expanded network HTTP smoke.
Final run IDs, counts, failures and limitations will be recorded after execution.
