# CommerceFlow roadmap

## Current increment — CF-01 and catalog foundation
- [x] Java 21, Maven, Spring Boot, JPA, validation, centralized errors, OpenAPI and metrics.
- [x] Flyway schema, local Docker Compose and environment examples.
- [x] Opaque bearer authentication, registration as CUSTOMER, explicit ADMIN bootstrap, self-profile and logout.
- [x] Product creation by ADMIN; public paginated/searchable catalog.
- [x] Money and stock domain primitives.
- [ ] Verify unit, HTTP and real MySQL integration tests; record actual evidence.
- [ ] Merge first feature PR after checks.

## Subsequent increments
- CF-02: complete identity lifecycle and product administration after the foundation.
- CF-03: order snapshots, ownership, transactional stock reservation, scoped idempotency and outbox in one transaction.
- CF-04: simulated payment state machine, cancellation and reservation expiry; release/consume stock exactly once.
- CF-05: Kafka outbox publishing, inbox deduplication, bounded retries, DLQ and safe replay.
- CF-06: optional Redis rate limiting/cache, audit operations, expanded observability, failure demonstrations and interview case study.

The order-processing MVP is not complete in the first catalog increment. Kafka/Redis and order processing are not implemented or claimed yet.
