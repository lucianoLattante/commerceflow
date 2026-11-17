# Reproducible local MVP demonstration

This guide describes implemented behavior. [VERIFICATION.md](VERIFICATION.md) records what actually ran.
All payments are synthetic. Source is original portfolio work, separate from employment experience.

## Start and inspect

Generate local credentials using scripts/init-env.ps1, then run docker compose up --build as in the
[README](../README.md). Existing .env files are preserved. Docker hosts must support the documented
MySQL/Kafka/Redis images. Open /docs for OpenAPI and /actuator/health/readiness for dependency readiness.

Use generated credentials to authenticate through POST /api/auth/token. Send the returned token in an
explicit Bearer header. Register a CUSTOMER separately; public registration cannot assign ADMIN.
Never put real provider credentials into this demo.

## Show the domain and failure boundaries

1. ADMIN creates a product. CUSTOMER creates an order using an Idempotency-Key. Repeat the request:
   the same order returns without another reservation/outbox/audit effect.
2. Pay an order twice with the same simulated decision/key. Show one terminal transition and immutable
   price/name snapshots. Cancellation/decline release stock; the expiry worker releases overdue reservations.
3. Observe the separate Kafka projection reaching sequence two. Explain broker acknowledgement versus
   the later MySQL sent mark, inbox deduplication and exact-byte operator recovery.
4. Read an ADMIN product version, reserve units and attempt an old revision: conflict preserves stock.
   Deactivate and finish an already reserved order at its original price.
5. Promote a CUSTOMER. Its old token is rejected; a fresh login has ADMIN access. Demote/disable/reactivate:
   old tokens remain revoked. Preserve at least one active administrator.
6. Inspect /api/admin/operations/maintenance and the paged delivery/DLQ views. Explain why token cleanup
   may delete expired credentials but must retain inbox, idempotency and audit evidence.

The disposable CI script scripts/smoke-orders.py executes the HTTP ownership/lifecycle/projection/
catalog/identity/admission flow, plus read-only maintenance pages. It accepts only loopback targets.
It creates test fixtures and uses the supplied bootstrap credentials; it is for an isolated database.
Do not run it against shared live data. The CI workflow supplies synthetic values.

## Automated failure demonstrations

Run mvn -B --no-transfer-progress verify with Java 21, Maven and a working Docker daemon.
Integration tests start disposable Testcontainers databases/brokers; they do not use Compose data.

| Evidence | Demonstration |
| --- | --- |
| OrderFlowIT | Concurrency, rollback, scoped idempotency, snapshots, lifecycle and audit |
| KafkaDeliveryIT | Ack/DB-mark crash window, duplicate records/inbox, poison recovery and replay budget |
| RedisGuardIT | Atomic quotas, spoofed forwarding headers, Redis pause/recovery and no business effects |
| UserAdministrationIT | Last-admin races, stale principals, login/revocation race and audit rollback |
| OperationalMaintenanceIT | Exact expiry boundary, disjoint/locked batches, rollback/recovery and preservation of replay evidence |
| SchemaUpgradeIT | Populated V1/V2 upgrade preserving identity, order, token and reservation data |

Injected SQL failures and controlled pauses exist only in disposable tests. CI logs and exact commits
support the presentation; compilation, Mermaid diagrams and intended behavior are not test execution.

## Interview narrative

Start with the original modular monolith and its single MySQL commit boundary. Demonstrate a concrete
invariant, its failure case and the executed test. Explain why Kafka is at-least-once and why Redis
does not own stock/order state. Finish with a tradeoff: retained replay evidence grows until a
horizon/tombstone policy is designed; bounded cleanup is not a throughput benchmark.

OpenAPI, ADRs 1–9, the [case study](CASE_STUDY.md), API contracts and this guide are the review package.
The MVP has no real payment provider or production deployment. Email recovery/verification, refresh
tokens, MFA, complete retention, production broker/Redis security and capacity remain future work.
