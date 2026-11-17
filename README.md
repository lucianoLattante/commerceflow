# CommerceFlow

Original commerce backend by Luciano Lattante. Built incrementally as a **modular monolith**, with Java 21, Spring Boot, MySQL and explicit domain invariants.

**Verified local functional MVP.** Product/user administration, owned orders, simulated lifecycle, Kafka recovery, Redis admission, transactional audit and bounded expired-token maintenance run together in the documented local setup. CI 44104045854 passed 122 unit + 160 integration tests and the complete Compose/HTTP flow. See [the local demonstration](docs/DEMO.md) and [exact verification evidence](docs/VERIFICATION.md).

[Order creation](docs/ORDERS.md) · [Payment, cancellation and expiry](docs/LIFECYCLE.md) · [Kafka, inbox and DLQ](docs/MESSAGING.md) · [Admission, audit and metrics](docs/OPERABILITY.md)

[Español](README.es.md) · [Architecture](docs/ARCHITECTURE.md) · [Decisions](docs/adr/0001-modular-monolith.md) · [Roadmap](ROADMAP.md)

Windows/Swagger walkthrough: [Probar en local](docs/LOCAL_TESTING.es.md).

## Run locally

Requirements: Docker Engine with Compose v2. For host builds: Java 21 and Maven 3.6.3+.

On Windows, generate independent random local credentials without overwriting an existing environment file:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/init-env.ps1
docker compose up --build
```

The script creates an ignored `.env`. For an older environment lacking its independent Redis password, run `scripts/add-redis-env.ps1`; it preserves existing settings. The local administrator email is `admin@example.test`; its randomly generated password is in that local file. The application does not contain a default administrator password.

Services bind to loopback: API <http://localhost:8080>, MySQL port 3306, Kafka port 9092 and authenticated Redis port 6379. Use an isolated local database. `docker compose down` stops the services and retains the volume; deleting that volume would delete local data.

A copied `.env.example` requires replacement values; its administrator placeholder deliberately fails password validation. Spring Boot does not automatically load the Compose `.env` when invoked directly through Maven: host execution requires exporting the corresponding CF_* environment variables.

## API walkthrough

Public catalog and health:

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail 'http://localhost:8080/api/products?page=0&size=20&q=book'
```

Authenticate using the generated local administrator credentials:

```http
POST /api/auth/token
Content-Type: application/json

{"email":"admin@example.test","password":"<your-local-admin-password>"}
```

Use the returned `accessToken` explicitly in the Authorization header:

```http
POST /api/products
Authorization: Bearer <accessToken>
Content-Type: application/json

{"sku":"BOOK-001","name":"Distributed Systems Notebook","amount":12.34,"currency":"USD","stock":10}
```

The response is 201 with a Location header and the available quantity. Duplicate SKU returns 409. Invalid price/quantity returns 400; CUSTOMER writes return 403; missing/expired credentials return 401. Errors use ProblemDetail and a stable `code` field.

| Endpoint | Access |
| --- | --- |
| POST /api/auth/register | Public; always creates CUSTOMER |
| POST /api/auth/token | Public credential exchange |
| POST /api/auth/logout | Bearer; revokes the presented token |
| GET /api/users/me | Bearer; current user only |
| POST /api/orders | Bearer + Idempotency-Key |
| GET /api/orders | Bearer; own summaries |
| GET /api/orders/{id} | Owner or ADMIN |
| GET /api/orders/{id}/projection | Owner or ADMIN; asynchronous view |
| GET /api/orders/{id}/audit | Owner or ADMIN; durable transition history |
| GET /api/admin/operations/audit | ADMIN; paginated/filterable audit |
| GET /api/admin/messaging/failures, /dead-letters | ADMIN; metadata only |
| POST /api/admin/messaging/failures/{id}/retry, /dead-letters/{id}/replay | ADMIN + Idempotency-Key |
| POST /api/orders/{id}/simulated-payment | Owner + Idempotency-Key; simulator only |
| POST /api/orders/{id}/cancel | Owner; idempotent cancellation |
| GET /api/products, /api/products/{id} | Public |
| POST /api/products | ADMIN |
| GET /actuator/prometheus | ADMIN |
| GET /docs, /v3/api-docs | Public API documentation |

Example registration body: `{"email":"customer@example.test","password":"<a-local-passphrase>"}`. Email is an unverified account identifier; no email delivery or ownership verification is implemented.

Search treats % and _ literally. Page size is 1–100 and ordering is by unique SKU. Supported currencies are USD, ARS and EUR; prices use two decimal places, never floating point.

## Authentication and scope

Bearer tokens contain 256 random bits, expire after 15 minutes and are stored only as SHA-256 digests. Each request checks expiry, active-user state and the current role in MySQL; logout revokes immediately. Passwords use BCrypt with work factor 12 and a 72 UTF-8 byte maximum.

No authentication cookies, Basic authentication or form login are enabled. CSRF is disabled for this explicit-header API; adopting cookie authentication would require a new CSRF decision.

ADMIN bootstrap is explicit environment configuration. Existing administrator credentials are preserved rather than silently reset. A conflicting CUSTOMER bootstrap account fails startup instead of being promoted.

Redis admission, role/activation administration and expired-token cleanup are implemented. Identity recovery/verification, MFA and refresh tokens remain deferred. This is a local MVP; no production deployment or distributed trace export is claimed.

The order increment is documented in [ORDERS.md](docs/ORDERS.md) and [ADR 3](docs/adr/0003-transactional-order-creation.md). [Kafka publication, inbox and DLQ](docs/MESSAGING.md) extend the durable outbox. Delivery is at least once; the consumer deduplicates committed effects.

## Tests

```bash
mvn test
mvn verify
```

- test runs database-free unit tests: **122 passed locally and in CI**. Local runtime: Oracle JDK 21.0.7 / Maven 3.9.0.
- verify adds disposable Testcontainers MySQL/Kafka/Redis execution: **160 integration tests passed in CI**. A working Docker daemon is required; unavailable Docker is not silently skipped.
- [CI 44104045854](https://github.com/lucianoLattante/commerceflow/actions/runs/44104045854) passed all **282 JUnit cases**, plus Compose configuration/build/startup and actual HTTP domain/maintenance/admission flows.

Local integration classes compiled but did not execute because the Docker daemon was unavailable.
[VERIFICATION.md](docs/VERIFICATION.md) retains exact source commits, commands, suite breakdown,
corrected local test failures and all historical checkpoints. Static validation is not runtime testing.

## Technical evidence

- [Architecture and domain](docs/ARCHITECTURE.md)
- [ADR 1: modular monolith](docs/adr/0001-modular-monolith.md)
- [ADR 2: opaque bearer credentials](docs/adr/0002-opaque-tokens.md)
- [ADR 4: order lifecycle](docs/adr/0004-order-lifecycle.md)
- [ADR 5: durable messaging](docs/adr/0005-durable-kafka.md)
- [ADR 6: admission/audit/metrics](docs/adr/0006-admission-audit-metrics.md)
- [Case study and interview discussion](docs/CASE_STUDY.md)

The repository contains original portfolio code, with no employer source or production data.

## Administration, maintenance and interview demonstration

- [Catalog revisions](docs/CATALOG_ADMINISTRATION.md): shared inventory locking/versioning, deactivation and atomic before/after audit.
- [User administration](docs/IDENTITY_ADMINISTRATION.md): last-active-admin protection, login/revocation coordination and fresh authentication after role changes.
- [Retention and operator pages](docs/RETENTION.md): bounded expired-token cleanup; business/inbox/idempotency/audit evidence is retained.
- [Local MVP demonstration](docs/DEMO.md): reproducible setup, executed failure scenarios and interview narrative.
- [ADR 7](docs/adr/0007-catalog-revisions.md), [ADR 8](docs/adr/0008-identity-administration.md) and [ADR 9](docs/adr/0009-bounded-maintenance.md) explain the tradeoffs.

Payments are synthetic. Delivery is at least once. Complete replay-safe archival/retention, capacity
benchmarks, distributed tracing, production broker/Redis/identity hardening and deployment remain
future work. No performance numbers or production-readiness claim is made.
