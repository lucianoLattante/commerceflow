# CommerceFlow roadmap

## Current increment — CF-01 and catalog foundation
- [x] Java 21, Maven, Spring Boot, JPA, validation, centralized errors, OpenAPI and metrics.
- [x] Flyway schema, local Docker Compose and environment examples.
- [x] Opaque bearer authentication, registration as CUSTOMER, explicit ADMIN bootstrap, self-profile and logout.
- [x] Product creation by ADMIN; public paginated/searchable catalog.
- [x] Money and stock domain primitives.
- [x] 33 unit + 24 MySQL integration tests and Compose runtime checks passed; see docs/VERIFICATION.md.
- [x] First feature PR prepared and runtime-source checks passed; integration state is recorded in GitHub PR #1.

## Subsequent increments
- CF-02: complete identity lifecycle and product administration after the foundation.
- CF-03: order snapshots, ownership, transactional stock reservation, scoped idempotency and outbox in one transaction.
- CF-04: simulated payment state machine, cancellation and reservation expiry; release/consume stock exactly once.
- CF-05: Kafka outbox publishing, inbox deduplication, bounded retries, DLQ and safe replay.
- CF-06: optional Redis rate limiting/cache, audit operations, expanded observability, failure demonstrations and interview case study.

The order-processing MVP is not complete in the first catalog increment. Kafka/Redis and order processing are not implemented or claimed yet.

## Transactional order increment
- [x] Owned orders and immutable price/item snapshots; pagination/filtering.
- [x] Sorted product locks, scoped idempotency claim and durable outbox in one transaction.
- [x] Additive V2 migration; V1 preserved.
- [x] 54 unit tests passed locally; integration suite compiles.
- [x] CI run 43932466541 passed: 54 unit + 55 MySQL integration tests, upgrade preservation, late-failure rollback and real HTTP smoke.
- [x] Prepared for integration after verification; PR #2 records the merge state. Payment/expiry transitions and Kafka delivery remain pending.
