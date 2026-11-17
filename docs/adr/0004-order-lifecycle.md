# ADR 4 — transactional simulated payment and reservation lifecycle

Status: implemented in CF-04; execution evidence is recorded separately.

## Context

Creation reserved stock but had no terminal transition. A lost HTTP response, repeated cancellation,
or expiry worker racing with payment must not consume or release the same reservation twice.

## Decision

Keep the simulator, order state, inventory and completion outbox in one MySQL transaction.
Acquire the order row with PESSIMISTIC_WRITE first; then lock products in UUID order.
Use READ COMMITTED and read the injected clock after acquiring the order lock.
Only RESERVED can transition. PAID, PAYMENT_DECLINED, CANCELLED and EXPIRED are terminal.

Approval consumes on-hand and reserved quantities together. Decline, cancellation and expiry release
only reserved quantity. The order state, completion timestamp, payment digest/outcome (when applicable),
inventory writes and event commit or roll back together. An outbox failure after JPA flush must roll back all writes.

Payment requires a validated key, scoped to this order. Its SHA-256 digest and simulated decision are
retained on the terminal order. The same key/decision replays; a changed key or decision conflicts.
Decline is terminal: a new checkout is required to retry the business operation.
Cancellation is intrinsically idempotent; it has no separate key store.

The lease is exactly 15 minutes from persisted creation time; the deadline is inclusive.
A due RESERVED order is expired by the payment/cancel transaction itself and returned as EXPIRED with
HTTP 200. Throwing a runtime exception after expiring it would roll back the release, so the client
must inspect status. This response never means a payment was approved.

A separate scheduled worker scans at most 100 due IDs every 60 seconds, initially after 60 seconds.
Each ID enters its own proxied transaction. One failure leaves that reservation eligible for a later
scan and does not roll back other orders. Multiple worker instances can select the same ID;
the locked terminal state makes subsequent attempts no-ops. This is not an exact expiry-time SLA.
The flag commerceflow.reservations.scheduling-enabled=false disables scanning for deterministic tests.

## Alternatives

- Optimistic CAS plus stock updates: viable, but requires careful retry orchestration across all rows.
  Explicit locking provides a smaller observable transaction for this increment.
- Independent payment service/saga: appropriate for a real asynchronous provider, but would add
  compensation and uncertain outcomes that a synchronous local simulator does not need.
- Redis lease: would introduce another consistency boundary. MySQL remains the source of truth.

## Consequences and limits

The simulator accepts APPROVED or DECLINED from the authenticated owner for demonstration purposes.
It never accepts card/bank data, contacts a provider or moves real money. ADMIN may read another
order, but cannot pay or cancel it. There is no refund or reopening path.

JVM clocks must be synchronized if multiple application instances are used. SQL stores microsecond
precision; application transition times use that precision. Stock constraints prevent negative values
but do not reconstruct reservation ownership after arbitrary manual database edits.

The schema extends V2 through V3; previously created reservations receive created_at + 15 minutes.
Existing expired reservations become eligible on the next scan. V1 and V2 remain immutable.

No external exactly-once guarantee, distributed payment, Kafka publication or full operator audit
trail is claimed. Digest retention currently follows order retention; cleanup is a future policy.
