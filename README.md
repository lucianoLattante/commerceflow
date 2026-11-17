# CommerceFlow

Original commerce backend by Luciano Lattante. Built incrementally as a **modular monolith**, with Java 21, Spring Boot, MySQL and explicit domain invariants.

**Current status: verified durable messaging increment.** The order lifecycle now feeds a leased Kafka publisher and an idempotent asynchronous projection, with bounded retries, durable DLQ and audited ADMIN replay. No real payment/provider or public deployment is involved. Redis and expanded operability remain planned.

[Order creation](docs/ORDERS.md) · [Payment, cancellation and expiry](docs/LIFECYCLE.md) · [Kafka, inbox and DLQ](docs/MESSAGING.md)

[Español](README.es.md) · [Architecture](docs/ARCHITECTURE.md) · [Decisions](docs/adr/0001-modular-monolith.md) · [Roadmap](ROADMAP.md)

## Run locally

Requirements: Docker Engine with Compose v2. For host builds: Java 21 and Maven 3.6.3+.

On Windows, generate independent random local credentials without overwriting an existing environment file:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/init-env.ps1
docker compose up --build
```

The script creates an ignored `.env`. The local administrator email is `admin@example.test`; its randomly generated password is in that local file. The application does not contain a default administrator password.

Services bind to loopback: API <http://localhost:8080>, MySQL port 3306 and Kafka port 9092. Use an isolated local database. `docker compose down` stops the services and retains the volume; deleting that volume would delete local data.

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

Account recovery, verification, refresh tokens, token cleanup, login rate limiting and full user/role administration are pending. This increment is intended for local evaluation; no public deployment is claimed.

The order increment is documented in [ORDERS.md](docs/ORDERS.md) and [ADR 3](docs/adr/0003-transactional-order-creation.md). [Kafka publication, inbox and DLQ](docs/MESSAGING.md) extend the durable outbox. Delivery is at least once; the consumer deduplicates committed effects.

## Tests

```bash
mvn test
mvn verify
```

- `test`: database-free unit tests. **82 passed in CF-05 CI**. The earlier local checkpoint ran 54 with JDK 21.0.7 and Maven 3.9.0; no new local run is claimed while the workstation is unavailable.
- `verify`: **95 MySQL/Kafka integration tests passed in CF-05 CI**, including order lifecycle, real Kafka delivery/recovery, concurrency, rollback and migration preservation, using disposable MySQL 8.4.11 and Kafka 4.1.1 through Testcontainers 2.0.5. It requires a working Docker daemon and fails when Docker is unavailable; it is not silently skipped.
- [CI](.github/workflows/verify.yml): runs verify, validates Compose, builds/starts local containers and checks actual HTTP health/catalog responses. The [foundation checkpoint](docs/VERIFICATION.md) records the initial baseline; current PR checks track the additional order/rollback/concurrency suite.

The unit result alone is not evidence of MySQL concurrency or rollback. OrderFlowIT and SchemaUpgradeIT exercise those properties using disposable databases; consult their actual CI results. [CF-05 run 43992169237](https://github.com/lucianoLattante/commerceflow/actions/runs/43992169237) passed all 177 tests and Compose/HTTP checks; see the exact source commit and limits in [VERIFICATION.md](docs/VERIFICATION.md).

## Technical evidence

- [Architecture and domain](docs/ARCHITECTURE.md)
- [ADR 1: modular monolith](docs/adr/0001-modular-monolith.md)
- [ADR 2: opaque bearer credentials](docs/adr/0002-opaque-tokens.md)
- [ADR 4: order lifecycle](docs/adr/0004-order-lifecycle.md)
- [ADR 5: durable messaging](docs/adr/0005-durable-kafka.md)
- [Case study and interview discussion](docs/CASE_STUDY.md)

The repository contains original portfolio code, with no employer source or production data.
