# CommerceFlow engineering conventions

- This is an original portfolio project owned by Luciano Lattante. No employer source, data or internal names.
- Java 21, Spring Boot 3.5 line. Check official compatibility before dependency changes.
- Modular monolith: catalog and identity expose application services; inventory keeps stock invariants. Orders coordinate the creation transaction; the outbox adapter persists events. Messaging uses a transport queue and atomic inbox/projection. Broker I/O must remain outside the order transaction.
- Constructor injection, validated DTOs, explicit roles, ProblemDetail errors and Flyway migrations.
- Money uses BigDecimal with explicit supported currency and two decimal places. Never floating-point money.
- Bearer credentials are random and stored as SHA-256 digests; no authentication cookies or Basic login. Never log tokens/passwords.
- No default administrator password. Bootstrap only from explicit environment configuration, never overwrite existing credentials.
- Existing migrations are immutable after release. Schema changes use a new migration.
- Unit tests are database-free. Integration tests use a disposable Testcontainers MySQL; no live credentials/providers.
- Run mvn verify before calling the increment verified. Docker-unavailable tests are failures, not silently skipped.
- Feature branches and PRs; preserve unrelated work. No force-push or deployment without explicit authorization.
- README and ROADMAP distinguish implemented, verified and planned behavior.

- Order creation must retain the single transaction across JPA stock/order writes and JDBC idempotency/outbox. Test every new failure boundary with real MySQL.

- Preserve event identity/bytes across retries; a Kafka ack and MySQL SENT mark are separate commits.
- Fence delivery updates with lease tokens. Preserve source sequence gates.
- Inbox/projection and durable DLQ/transport recovery each require a single DB transaction.
- Operator replay is ADMIN-only, bounded, idempotent, audited and cannot edit payloads.

- Admission runs after authorization. Do not trust client forwarding headers or block logout.
- Enabled admission fails closed on Redis errors. Redis does not hold authoritative order/stock state.
- Order audit joins the business transaction; committed-transition metrics run only after commit.
- Never put identities/credentials into metric labels. Unknown observations must not become false zeroes.
