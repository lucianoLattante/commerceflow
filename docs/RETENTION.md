# Retention and operational maintenance

This local MVP deletes **only expired access-token rows**. It does not delete business events,
deduplication evidence, orders or audit history. Authentication already rejects expired tokens before
physical cleanup; scheduling does not extend validity or replace immediate administrative revocation.

## Retention policy

| Data | Implemented policy |
| --- | --- |
| access_tokens | Expired rows eligible at expires_at <= server cutoff; bounded scheduled deletion |
| Users/products | Retained; disable/deactivate instead of delete |
| Orders, lines and business outbox | Retained; preserve snapshots and recovery evidence |
| Order idempotency claims | Retained; do not reopen previously used keys |
| Dispatch, inbox and projections | Retained; preserve duplicate detection, sequencing and replay |
| DLQ and operator requests | Retained; preserve exact bytes, replay budgets and operator idempotency |
| Order/product/user audit | Retained; no audit deletion endpoint |

There is no blanket age-based purge for those retained records. Deleting an inbox entry can make an
old event look new; deleting an idempotency claim can duplicate an operation; deleting transport
state can lose recovery context. Choosing replay horizons, archives and safe tombstones requires a
separate design. This policy intentionally trades storage growth for demonstrable MVP correctness.

## Expired-token worker

Host execution defaults off. Compose defaults on and supports these optional settings without
changing existing credentials:

| Variable | Default | Bound |
| --- | --- | --- |
| CF_TOKEN_CLEANUP_ENABLED | false on host; true in Compose | true/false |
| CF_TOKEN_CLEANUP_BATCH_SIZE | 200 | 1..1000 |
| CF_TOKEN_CLEANUP_INTERVAL_MS | 60000 | 1000..3600000 |

Invalid bounds fail configuration binding. No .env is overwritten. The first scan waits one interval
and subsequent scans use fixed delay. This is eventual cleanup, not a deletion-time SLA. Work per
scan is bounded; sustained backlog can exceed a worker's configured drain rate. No capacity was measured.

Each scan uses one READ COMMITTED MySQL transaction:

1. Capture one server-clock cutoff at microsecond precision.
2. Select at most batchSize expired digests in expires_at/digest order using FOR UPDATE SKIP LOCKED.
3. Delete only those locked primary keys, rechecking expiry with the same cutoff.
4. Commit; only then increment committed-run and deleted-row counters.

Digests remain internal and are never returned/logged by this component. Per-key deletes keep the
write set confined to claimed rows. Multiple workers skip locked rows instead of claiming the same
batch. Skipped rows remain eligible for later scans. Expiry is immutable during issuance; the
existing expiry index is preserved and no schema migration is needed. V1-V7 remain unchanged.

Any deletion failure rolls back the whole batch, including earlier per-key deletes. A worker records
a failed attempt and leaves rows for a later tick. Metric failures cannot turn a committed deletion
into an application failure. Clock skew, DB contention/outages and process restarts remain operational
limitations. Counters are process-local observations and reset on restart.

## Read-only maintenance API and metrics

GET /api/admin/operations/maintenance requires ADMIN at HTTP and service boundaries. It returns the
enabled flag, batch/interval settings, expired count, oldest expired timestamp and observation time.
It contains no token/user identifiers, digests, passwords or raw credentials. There is no HTTP cleanup
command or caller-controlled expiry cutoff.

Prometheus metrics remain ADMIN-only:

- commerceflow.identity.tokens.expired: current DB observation; NaN if unavailable.
- commerceflow.identity.cleanup.runs{outcome=committed|failed}: committed scans versus failed attempts.
- commerceflow.identity.cleanup.deleted: rows counted after commit.

A metadata DB failure is an error, not a healthy empty result. Observations are not a cross-system
snapshot. See [executed evidence](VERIFICATION.md).

## Paged operator visibility

Existing limited-list routes remain compatible. New metadata-only routes:

```http
GET /api/admin/messaging/deliveries?state=FAILED&kind=ORDER&page=0&size=20
Authorization: Bearer <admin-token>

GET /api/admin/messaging/dead-letters/page?replayable=true&page=0&size=20
Authorization: Bearer <admin-token>
```

These routes enforce ADMIN in HTTP and application services. State defaults to FAILED; accepted
values are PENDING, IN_FLIGHT, SENT and FAILED. Optional kind is ORDER, DLQ or REPLAY. Dead-letter
replayable filtering is optional. Page is 0..10000 and size 1..100.

Delivery summaries include timestamps, attempts, retry cycles and error code. DLQ summaries include
source position, reason, replayability/count and creation time. They do not select payloads, message
keys or hashes. A replayable record with replayCount=3 has exhausted its budget; filtering it does not
grant another attempt. Retry/replay remain bounded audited actions with the existing key contract.

Ordering has an ID tie-breaker. Offset pages can shift under concurrent writes and become expensive
on large backlogs; they are bounded responses, not a proven large-volume cursor/retention solution.
No automatic poison deletion, blind replay or payload-edit endpoint is added.
