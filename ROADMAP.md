# CommerceFlow roadmap

The original Java 21 / Spring Boot modular monolith grows through small, verified feature PRs.
Implemented functionality is listed below; Kafka and Redis are still planned.
The distributed-processing MVP is not complete and no production deployment is claimed.

## Verified increments

| Increment | Implemented behavior | Execution evidence |
| --- | --- | --- |
| CF-01 | Identity, opaque bearer auth, explicit admin bootstrap, catalog, money/stock primitives, Flyway, Compose, OpenAPI and metrics | Initial 33 unit + 24 MySQL tests; PR #1 |
| CF-03 | Owned order snapshots, sorted inventory locks, scoped creation idempotency and transactional outbox | 54 unit + 55 MySQL tests and HTTP smoke; PR #2 |
| CF-04 | Simulated approval/decline, owner cancellation, 15 minute reservation expiry and terminal-state guards | 65 unit + 78 MySQL tests and expanded Compose HTTP smoke; PR #3 |

[Exact commands, commits and CI evidence](docs/VERIFICATION.md).

## Completed in CF-04

- [x] Consume or release stock once per reservation through a locked terminal transition.
- [x] Commit order state, stock, payment digest/outcome and completion outbox in one transaction.
- [x] Replay an equivalent simulated payment; conflict on changed key/outcome.
- [x] Repeated cancellation/expiry has no second stock effect.
- [x] Bound expiry scans to 100 candidates and use independent transactions per order.
- [x] Preserve released V1/V2 migrations; backfill existing reservations through V3.
- [x] Execute MySQL races, late-failure rollback/retry and migration tests.
- [x] Execute Docker Compose startup/readiness and network HTTP lifecycle checks.
- [x] Document state machine, API contract, tradeoffs, limitations and interview demonstrations.
- [x] Integrated PR #3; post-merge CI 43986864165 passed.

## Current — CF-05: durable messaging (verification pending)

- [ ] Decide and document Kafka topic/event contracts and publisher acknowledgement boundaries.
- [ ] Publish pending outbox events with a recoverable claim/retry strategy.
- [ ] Demonstrate the broker-ack/database-mark crash window and possible duplicate delivery.
- [ ] Add an idempotent consumer/inbox with its state update in one database transaction.
- [ ] Bound retries, route exhausted failures to a DLQ and define safe replay.
- [ ] Use disposable Kafka/MySQL tests for duplicates, recovery and poison messages.
- [ ] Extend Compose, CI and the technical case study with executed evidence.

## Later — CF-06: operability and security follow-up

- [ ] Add justified Redis rate limiting/cache with observable fallback behavior.
- [ ] Add operation audit, lifecycle/outbox metrics and failure demonstrations.
- [ ] Extend API filters/administration where they support a concrete use case.
- [ ] Finish the interview case study and reproducible demonstration.

## Deferred CF-02 identity maintenance

Registration/login/self-profile/logout and role-aware access are implemented.
Full user/role administration, email verification/recovery, refresh tokens, token cleanup
and login throttling remain deferred. There is no claim that the complete identity lifecycle is finished.

## Known boundaries

Payments are synthetic; no real money, card/bank data, refund or external provider is involved.
Outbox rows are not yet Kafka delivery. Expiry has no exact timing SLA, and multiple JVMs would
require synchronized clocks. A complete dependency advisory audit, production load benchmark
and public deployment remain outside the executed evidence.

CF-05 implements a durable transport queue, leased at-least-once publisher, ordered source events,
an atomic inbox/projection, bounded retries, durable DLQ plus Kafka DLQ transport, and audited
idempotent ADMIN replay/retry. The current branch awaits actual MySQL/Kafka and Compose execution;
no passing count is claimed for this increment yet.
