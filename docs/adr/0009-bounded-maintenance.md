# ADR 9: bounded credential cleanup, retained replay evidence and metadata paging

Status: accepted for local MVP maintenance.

## Context

Expired credentials accumulate even though authentication rejects them. Business idempotency and
inbox/transport records also grow, but their age alone does not establish that they are safe to erase.
Limited failure lists cannot browse a backlog beyond their first page.

## Decision

Delete only expired access-token rows, with a server-owned cutoff, bounded MySQL transaction and
FOR UPDATE SKIP LOCKED selection. Delete selected primary keys and recheck expiry. Publish deletion
counts after commit, allow failed batches to retry on later scheduled ticks, and expose aggregate
ADMIN metadata without a remote cleanup command. Scheduling is off by default on host and on in Compose.

Retain orders, claims, business/transport outboxes, inbox, projections, DLQ budgets and all audits.
Add bounded, typed, ADMIN metadata pages for delivery and dead-letter investigation. Preserve the
existing list routes. Never fetch payloads or hashes merely to render operational lists.

## Alternatives and tradeoffs

- A single unbounded DELETE is shorter, but increases write/lock duration and rollback scope.
- Offset-based selection for deletion can skip shifting rows. Row locks and SKIP LOCKED claim
  concrete batches; a later scan can revisit skipped rows.
- Individual primary-key deletes cost more statements than a bulk purge, but keep each write on
  claimed rows with an explicit expiry predicate. No throughput claim is made.
- Redis TTL is suitable for admission windows, not authoritative MySQL credential ownership.
- Deleting old inbox/claims without a replay/key horizon can reopen previously applied operations.
  Archiving and tombstones need an explicit future protocol; no speculative purge is implemented.
- Cursor paging/index tuning can improve large backlogs, but requires measured access patterns.
  The selected offset pages are bounded operator views with stable tie-breakers, not snapshot exports.

No schema change is necessary; released V1-V7 are immutable. There is no complete retention/erasure,
production observability, capacity or public deployment claim. Existing exact-byte retry/replay and
application-level append-only audits retain their documented limitations.
