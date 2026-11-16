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
