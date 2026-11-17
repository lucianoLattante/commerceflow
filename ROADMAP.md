# CommerceFlow roadmap

Original Java 21 / Spring Boot modular monolith with explicit commit boundaries.
Order creation, simulated lifecycle and durable Kafka projection/recovery are implemented and verified.
Redis and expanded operability remain planned; no production deployment is claimed.

## Verified increments

| Increment | Behavior | Evidence |
| --- | --- | --- |
| CF-01 | Identity, catalog, money/stock, Flyway, Compose, errors/OpenAPI/metrics | Initial 33 unit + 24 MySQL tests; PR #1 |
| CF-03 | Owned snapshots, sorted stock locks, scoped idempotency, atomic outbox | 54 unit + 55 MySQL tests; PR #2 |
| CF-04 | Simulated payments, cancellation and 15 minute reservation expiry | 65 unit + 78 MySQL tests; PR #3; post-merge CI 43986864165 passed |
| CF-05 | Leased Kafka dispatch, atomic inbox/projection, durable DLQ and audited recovery | 82 unit + 95 MySQL/Kafka tests; CI 43992169237; PR #4 |

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

## Next — CF-06: operability and security follow-up

- [ ] Add justified Redis rate limiting/cache with observable fallback behavior.
- [ ] Add operation audit beyond message recovery, lifecycle/outbox metrics and failure demonstrations.
- [ ] Improve admin metadata queries, operator visibility, retention and poison-backlog handling.
- [ ] Extend concrete product/identity administration use cases.
- [ ] Finish the reproducible demonstration and case study.

## Deferred identity maintenance

Registration/login/self-profile/logout, explicit admin bootstrap and role-aware access are implemented.
Full user/role administration, email verification/recovery, refresh tokens, token cleanup and login
throttling remain deferred. No complete identity-lifecycle claim is made.

## Known boundaries

Payments are synthetic. No real money/provider/refund, production Kafka ACL/TLS, multi-broker
resilience, complete dependency advisory audit, load benchmark or public deployment is verified.
At-least-once messaging relies on inbox deduplication; no external exactly-once guarantee is claimed.
Expiry has no exact timing SLA, and leases require synchronized clocks across publisher instances.
