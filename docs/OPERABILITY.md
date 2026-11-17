# Admission, audit and operational evidence

CF-06 adds Redis admission, an order audit and low-cardinality operational metrics.
It does not add speculative product caching or change order monetary invariants.

## Admission contract

When commerceflow.rate-limit.enabled=true:

| Scope | Default allowance | Identity |
| --- | --- | --- |
| POST /api/auth/token | 5 per 60 seconds | Servlet socket address |
| POST /api/auth/register | 3 per 60 seconds | Servlet socket address |
| Other authorized API writes | 60 per 60 seconds | Authenticated actor UUID |

Authorization precedes the limiter: anonymous/forbidden writes are rejected before quota use.
Logout and reads are exempt. All authentication attempts count, including invalid credentials.
A denied allowance returns 429/rate_limited and Retry-After. Redis failure returns
503/rate_limit_unavailable before controller execution, with Retry-After: 1; there is no
permissive local fallback. The same order idempotency key remains available after failed admission.

Lua performs counter checking, increment and expiry atomically in Redis.
Rejected requests do not inflate the counter. An exhausted counter without a TTL is repaired
and remains denied; a zero-millisecond remaining TTL is still denied until actual expiry.
The server TTL controls windows independently of application-clock changes.

Redis keys contain policy and SHA-256 identity digest. No raw address, password or bearer credential
is stored in keys or metric labels. Address hashing is pseudonymization, not protection against a
dictionary search. Forwarding headers are ignored and server forward-header handling is disabled.
A trusted-proxy design is required before deployment behind a proxy; many users behind NAT currently
share an address allowance. Fixed-window boundary bursts, distributed attackers and Redis restarts
resetting ephemeral quotas are known limits.

Host admission defaults off via CF_RATE_LIMIT_ENABLED=false. Compose enables it against authenticated
Redis 8.2.11 on its internal network. External Redis port 6379 binds only to loopback. Redis is
ephemeral, non-root and read-only apart from temporary configuration; its password is written to a
private temporary config, not passed as a redis-server command-line argument.

New local environments:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/init-env.ps1
docker compose up --build
```

For an existing .env missing CF_REDIS_PASSWORD:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/add-redis-env.ps1
```

The helper adds an independent random value, preserves previous bytes/settings and refuses to
replace an existing Redis password. Neither helper prints secret values. No existing environment
file is overwritten.

The custom admission readiness component reports OUT_OF_SERVICE when enabled protection cannot
reach Redis. DB readiness alone is no longer enough for accepting protected writes. Public reads,
logout and authenticated metrics remain accessible while Redis is unavailable. Health details are
not public. Disabling the feature reports its disabled mode without requiring a Redis connection.

## Durable order audit

V5 creates order_operation_audit without backfilling historical actors or actions.
New reservations and terminal transitions record order ID, optional actor ID, action, sequence and
occurrence time in the same transaction as order/stock/idempotency/outbox/transport effects.
A later failure rolls back audit too. Equivalent retries and repeated terminal no-ops do not add rows.
Automatic expiry records a null actor, including when a request triggers deadline enforcement.
An older order created before V5 may only have a later completion entry; no missing history is fabricated.

- GET /api/orders/{id}/audit: owner or ADMIN, ascending transition sequence.
- GET /api/admin/operations/audit?page=0&size=20&action=ORDER_PAID: ADMIN only;
  page 0..10000 and size 1..100.

Audit entries exclude credentials, request bodies, addresses and provider data.
The application exposes no audit update/delete API; this is not a claim of tamper-proof storage
against a database administrator. Other business-operation audit and retention policies remain future work.

## Metrics and their meaning

GET /actuator/prometheus requires ADMIN bearer authentication.

| Metric family | Meaning |
| --- | --- |
| commerceflow_order_transitions_total | Confirmed order transitions, labeled by fixed action |
| commerceflow_rate_limit_requests_total | Allowed/denied/unavailable admission calls by fixed policy |
| commerceflow_dispatch_attempts_total | Actual broker acknowledged/transport-failed send attempts |
| commerceflow_dispatch_bookkeeping_total | Stale lease or failed database delivery marking |
| commerceflow_dispatch_messages | Read-time count by fixed transport state |
| commerceflow_dispatch_oldest_unconfirmed_seconds | Oldest non-SENT transport age |
| commerceflow_dead_letters | Retained recovery record count |

Business counters run only after transaction commit. An outbox failure after audit registration
must not increment them. Failure of metric recording cannot undo a confirmed business transaction
or prevent a confirmed dispatch from being marked. Delivery attempts are not business transitions;
an acknowledged send can coexist with a failed database mark and later duplicate delivery.

Gauges perform bounded-shape aggregate queries and never read message payloads. Database observation
failure returns NaN rather than a misleading zero. These separate queries are not one atomic
snapshot and are not a load-capacity guarantee. Process counters reset on restart; durable audit
is the historical record. No user/order/event/address/token value is used as a metric label.

## Verification boundaries

RedisGuardIT uses actual disposable MySQL and Redis, including Docker pause/unpause.
It exercises concurrent limiter instances, TTL expiry/repair, spoofed forwarding headers,
policy/actor isolation, forbidden requests, outage without business writes, recovery and audit access.
OrderFlowIT injects audit/outbox failures and checks committed-counter semantics.
Existing Kafka recovery/concurrency tests remain part of mvn verify.
The Compose network smoke verifies order audit, ADMIN boundaries and actual HTTP throttling.

Consult [exact executed evidence](VERIFICATION.md); compilation is not integration execution.
Production proxy trust, Redis TLS/ACL/HA, complete abuse defense, retention, identity/product
administration, full advisory audit, load benchmarking and public deployment remain outside this increment.
