# CommerceFlow roadmap

Original Java 21 / Spring Boot modular monolith with explicit commit boundaries.
Order creation, simulated lifecycle and durable Kafka projection/recovery are implemented and verified.
Redis admission, transactional audits/metrics, product/user administration and bounded token maintenance are verified. The local functional MVP is handed off through docs/DEMO.md; production follow-ups remain below.

## Verified increments

| Increment | Behavior | Evidence |
| --- | --- | --- |
| CF-01 | Identity, catalog, money/stock, Flyway, Compose, errors/OpenAPI/metrics | Initial 33 unit + 24 MySQL tests; PR #1 |
| CF-03 | Owned snapshots, sorted stock locks, scoped idempotency, atomic outbox | 54 unit + 55 MySQL tests; PR #2 |
| CF-04 | Simulated payments, cancellation and 15 minute reservation expiry | 65 unit + 78 MySQL tests; PR #3; post-merge CI 43986864165 passed |
| CF-05 | Leased Kafka dispatch, atomic inbox/projection, durable DLQ and audited recovery | 82 unit + 95 MySQL/Kafka tests; CI 43992169237; PR #4 |
| CF-06 admission/audit | Atomic Redis admission, order audit, committed counters and queue metrics | 90 unit + 108 MySQL/Kafka/Redis tests; CI 44083936277; PR #5 |

[Exact source commits, commands and limitations](docs/VERIFICATION.md).
GitHub PR pages record integration state separately from test success.

## Completed CF-05

- [x] Immutable business-event bytes plus transport intent in one transaction.
- [x] Committed leases and token-fenced updates, broker I/O outside DB locks.
- [x] Explicit source sequence gates and per-order Kafka keys.
- [x] Broker-ack/DB-mark crash-window test with duplicate records and one inbox effect.
- [x] Atomic inbox/projection; contiguous sequence and customer consistency.
- [x] Bounded publisher/consumer retries and abandoned-lease recovery.
- [x] Durable DLQ capture/transport transaction before recovered offset advancement.
- [x] ADMIN-only exact-payload replay/retry with hashed keys, budgets and actor audit.
- [x] Populated upgrade preservation; V1–V3 unchanged and V4 transport backfill.
- [x] Real MySQL/Kafka failure/concurrency/recovery tests and Compose HTTP projection smoke.
- [x] Architecture, ADR 5, API contract, limits and interview demonstration.

## Delivered CF-06 checkpoint and remaining maintenance

- [x] Add justified Redis admission with explicit outage behavior; no speculative product cache.
- [x] Add order-operation audit beyond message recovery, committed transition/delivery/queue metrics and failure demonstrations.
- [x] Make metadata-only admin queries avoid message payloads; expose audit pagination.
- [x] Paged operator metadata, explicit retained-evidence policy and bounded expired-token cleanup.
- [ ] Replay-safe archival, cursor/index measurement and production poison-backlog policy.
- [x] Concrete versioned product administration with atomic audit.
- [x] ADMIN user directory, versioned role/activation changes and atomic token/audit updates.
- [x] Reproducible local MVP demonstration, ADRs and source-backed interview case study.

## Remaining identity maintenance

Registration/login/self-profile/logout, explicit admin bootstrap and role-aware access are implemented.
Login/registration throttling and role/activation administration are implemented. Email verification/recovery,
MFA and refresh tokens remain deferred; expired-token cleanup is implemented. No complete identity-lifecycle claim is made.

## Known boundaries

Payments are synthetic. No real money/provider/refund, production Kafka ACL/TLS, multi-broker
resilience, complete dependency advisory audit, load benchmark or public deployment is verified.
At-least-once messaging relies on inbox deduplication; no external exactly-once guarantee is claimed.
Expiry has no exact timing SLA, and leases require synchronized clocks across publisher instances.

## CF-06 executed checkpoint
- [x] Redis Lua concurrency, true TTL expiry and orphaned exhausted-counter repair.
- [x] Socket/actor identity boundaries, forwarding-header spoof resistance and authorization before quota.
- [x] Redis pause/unpause with no order/claim/stock/audit writes during failed admission.
- [x] Logout/read/metrics availability and readiness degradation during the outage.
- [x] Audit rollback and after-commit counters; retries create no duplicate audit entries.
- [x] 90 local unit tests; environment scripts preserve existing values; Compose config validated.
- [x] CI 44083936277 passed 90 unit + 108 integration tests and complete Compose/HTTP checks.

This checkpoint leaves identity/product administration, retention/cleanup, full advisory audit,
load tests, production proxy/broker/Redis hardening and deployment explicitly deferred.

## Verified catalog administration increment

- [x] ADMIN discovery, detail and version-precondition revision; no product deletion.
- [x] Shared inventory row lock/version, absolute onHand/reserved invariant and inactive reservation guard.
- [x] Transactional before/after audit, pagination and V6; V1-V5 preserved.
- [x] 99 local unit tests passed; integration sources compiled.
- [x] CI 44089250548 passed 99 unit + 123 MySQL/Kafka/Redis integration tests and Compose HTTP administration smoke.
- [x] Review changed paths, released migrations, source hashes and runtime evidence; PR #6 records integration state.
- [x] User administration delivered in the following increment; remaining operations are tracked below.

## Verified user administration increment

- [x] ADMIN directory/filter/detail and versioned role/activation revisions.
- [x] Shared membership guard, persisted-operator revalidation and last-active-admin protection.
- [x] Serialized login issuance, atomic target-token revocation and before/after audit.
- [x] V7 preserving existing users/tokens; V1-V6 unchanged.
- [x] 108 local unit tests passed; integration sources compiled.
- [x] CI 44093544797 passed 108 unit + 146 real MySQL/Kafka/Redis tests and Compose/HTTP identity smoke.
- [x] Review paths, migrations, hashes and runtime evidence; PR #7 records integration state.
- [x] Bounded maintenance and final local MVP demonstration delivered below.

## Verified bounded maintenance and local MVP handoff

- [x] Expired-token batches, SKIP LOCKED claims, per-key deletion, rollback and after-commit metrics.
- [x] Read-only ADMIN maintenance metadata and bounded typed delivery/DLQ pages; legacy lists preserved.
- [x] Explicit retention policy preserves business/replay/audit evidence; no migration changes.
- [x] ADR 9, API/runbook, reproducible local MVP demo and interview guidance.
- [x] 122 local unit tests passed; integration compiled only locally.
- [x] CI 44104045854 passed 122 unit + 160 integration tests and complete Compose/HTTP maintenance smoke.
- [x] Source/migration/path review and local MVP handoff recorded; PR #8 records integration state.
- [ ] Production follow-ups: capacity, broker/Redis hardening, cursor/index measurement and replay-safe archival.

## Local testing guide — 2026-11-17
- [x] Spanish Windows/Swagger walkthrough with exact stock/state/idempotency expectations.
- [x] Credential handling, preserved volumes, expiry/role behavior and test-command prerequisites documented.
- [x] Runtime source unchanged from the verified MVP; documentation-only validation.

## Payment request diagnostics - 2026-11-22
- [x] Reused the verified 138-file main checkpoint and preserved existing user changes/credentials.
- [x] Investigated reported HTTP 400; documented example verified by binding tests and real Compose HTTP smoke.
- [x] Added redacted validation details, explicit Swagger examples and focused HTTP-binding regression tests.
- [x] Added safe local env/port/readiness diagnosis and Docker command guidance; existing local app mapping is 8081.
- [x] Local 128 unit tests passed; CI 45043457219 passed 128 unit + 160 MySQL/Kafka/Redis integration tests and real Compose/HTTP.
- [x] Redacted errors and the exact demo-payment-001 / APPROVED example verified; runtime source 57666ff24e450984d78bfcfbe47ebd018d58a33c.
- [ ] Original user request/body cannot be recovered; no unverified claim that its specific cause is established.

## Operator delivery pagination experiment

- Compare the current failed-delivery offset and count queries with a candidate index and an exclusive timestamp/ID cursor on 50,000 synthetic dispatches.
- Check equal pages across tied timestamps and record the plans in the disposable Compose verification job. [Reproduce the experiment](docs/CONTENTION.md#operator-delivery-pagination).
- Use the complementary [filter and write experiment](docs/CONTENTION.md#other-filters-and-write-cost) to evaluate the candidate index with the unfiltered, kind-only and combined state/kind queries and the cost of changing state on synthetic rows.
- Reproduce changes between page requests and measure concurrent commits on persistent InnoDB fixtures with and without the candidate index. [Verification procedure](docs/CONTENTION.md#pages-between-commits-and-concurrent-writes).
- Keep existing pagination semantics for callers. An opt-in cursor requires a decision about totals, filter changes between requests and whether the measured tradeoff justifies a persistent index.
