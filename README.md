# CommerceFlow

Original commerce backend by Luciano Lattante. Built incrementally as a **modular monolith**, with Java 21, Spring Boot, MySQL and explicit domain invariants.

**Current status: verified product/user administration, order lifecycle and operational recovery.** The order lifecycle now feeds a leased Kafka publisher and an idempotent asynchronous projection, with bounded retries, durable DLQ and audited ADMIN replay. No real payment/provider or public deployment is involved. CF-06 adds Redis-backed admission, transactional order audit and committed-operation metrics; the executed evidence is recorded below.

[Order creation](docs/ORDERS.md) · [Payment, cancellation and expiry](docs/LIFECYCLE.md) · [Kafka, inbox and DLQ](docs/MESSAGING.md) · [Admission, audit and metrics](docs/OPERABILITY.md)

[Español](README.es.md) · [Architecture](docs/ARCHITECTURE.md) · [Decisions](docs/adr/0001-modular-monolith.md) · [Roadmap](ROADMAP.md)

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

Redis login/registration/write admission is implemented in CF-06. Account recovery, verification, refresh tokens, token cleanup and full user/role administration remain pending. This increment is intended for local evaluation; no public deployment is claimed.

The order increment is documented in [ORDERS.md](docs/ORDERS.md) and [ADR 3](docs/adr/0003-transactional-order-creation.md). [Kafka publication, inbox and DLQ](docs/MESSAGING.md) extend the durable outbox. Delivery is at least once; the consumer deduplicates committed effects.

## Tests

```bash
mvn test
mvn verify
```

- `test`: database-free unit tests. **90 passed locally and in CF-06 CI**. Local runtime: JDK 21.0.7 and Maven 3.9.0 on 2026-11-17. Local Docker was unavailable, so container execution was verified in CI.
- `verify`: **108 MySQL/Kafka/Redis integration tests passed in CF-06 CI**, including order lifecycle, real Kafka delivery/recovery, concurrency, rollback and migration preservation, using disposable MySQL 8.4.11, Kafka 4.1.1 and Redis 8.2.11 through Testcontainers 2.0.5. It requires a working Docker daemon and fails when Docker is unavailable; it is not silently skipped.
- [CI](.github/workflows/verify.yml): runs verify, validates Compose, builds/starts local containers and checks actual HTTP health/catalog responses. The [foundation checkpoint](docs/VERIFICATION.md) records the initial baseline; current PR checks track the additional order/rollback/concurrency suite.

The unit result alone is not evidence of MySQL concurrency or rollback. OrderFlowIT and SchemaUpgradeIT exercise those properties using disposable databases; consult their actual CI results. [CF-06 run 44083936277](https://github.com/lucianoLattante/commerceflow/actions/runs/44083936277) passed all 198 tests and Compose/HTTP checks; see the exact source commit and limits in [VERIFICATION.md](docs/VERIFICATION.md).

## Technical evidence

- [Architecture and domain](docs/ARCHITECTURE.md)
- [ADR 1: modular monolith](docs/adr/0001-modular-monolith.md)
- [ADR 2: opaque bearer credentials](docs/adr/0002-opaque-tokens.md)
- [ADR 4: order lifecycle](docs/adr/0004-order-lifecycle.md)
- [ADR 5: durable messaging](docs/adr/0005-durable-kafka.md)
- [ADR 6: admission/audit/metrics](docs/adr/0006-admission-audit-metrics.md)
- [Case study and interview discussion](docs/CASE_STUDY.md)

The repository contains original portfolio code, with no employer source or production data.

## Verified product administration

ADMIN can discover inactive products and revise name, price/currency, active state and absolute on-hand
stock using an expected version. Existing order snapshots and reservations are preserved. Actor-attributed
before/after audit shares the product transaction. See the [API and retry contract](docs/CATALOG_ADMINISTRATION.md)
and [ADR 7](docs/adr/0007-catalog-revisions.md). Executed validation is tracked in [VERIFICATION.md](docs/VERIFICATION.md).
User administration is described below; retention remains deferred.

Catalog checkpoint CI 44089250548 passed **99 unit + 123 MySQL/Kafka/Redis integration tests**,
plus Compose startup and the actual HTTP flow. See [exact evidence](docs/VERIFICATION.md).

## Verified user administration

ADMIN directory and versioned role/activation updates now share an atomic state/token/audit commit.
A database guard preserves the last active administrator; login issuance shares the target user lock.
Promotion/reactivation require fresh authentication. See the [API and concurrency contract](docs/IDENTITY_ADMINISTRATION.md)
and [ADR 8](docs/adr/0008-identity-administration.md). CI 44093544797 passed **108 unit + 146 MySQL/Kafka/Redis integration tests**, with zero failures/errors/skips, plus Compose startup and actual HTTP identity flows.
Email recovery/verification, refresh tokens and retention remain deferred.

## Operational maintenance and local MVP demonstration

Bounded expired-token cleanup is enabled by Compose and off by default on host. It preserves live
credentials and all order/replay/audit evidence. ADMIN can read aggregate maintenance status and
paged delivery/DLQ metadata without fetching payloads. See the [retention contract](docs/RETENTION.md),
[ADR 9](docs/adr/0009-bounded-maintenance.md) and [reproducible local MVP demonstration](docs/DEMO.md).
Local unit verification: 122 passed. MySQL/Kafka/Redis and Compose/HTTP execution for this increment is pending CI.
