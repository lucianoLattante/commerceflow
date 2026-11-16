# CommerceFlow engineering conventions

- This is an original portfolio project owned by Luciano Lattante. No employer source, data or internal names.
- Java 21, Spring Boot 3.5 line. Check official compatibility before dependency changes.
- Modular monolith: catalog and identity expose application services; inventory keeps stock invariants. Add orders/outbox in later increments.
- Constructor injection, validated DTOs, explicit roles, ProblemDetail errors and Flyway migrations.
- Money uses BigDecimal with explicit supported currency and two decimal places. Never floating-point money.
- Bearer credentials are random and stored as SHA-256 digests; no authentication cookies or Basic login. Never log tokens/passwords.
- No default administrator password. Bootstrap only from explicit environment configuration, never overwrite existing credentials.
- Existing migrations are immutable after release. Schema changes use a new migration.
- Unit tests are database-free. Integration tests use a disposable Testcontainers MySQL; no live credentials/providers.
- Run mvn verify before calling the increment verified. Docker-unavailable tests are failures, not silently skipped.
- Feature branches and PRs; preserve unrelated work. No force-push or deployment without explicit authorization.
- README and ROADMAP distinguish implemented, verified and planned behavior.
