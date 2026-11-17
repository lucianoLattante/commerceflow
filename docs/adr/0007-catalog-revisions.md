# ADR 7: product revisions share inventory locking and versioning

Status: accepted for the catalog administration increment.

## Context

An administrator may change price, visibility and stock while orders reserve or consume units.
A naive read/overwrite can erase a reservation or restore already consumed stock. Deleting a product
would also conflict with persisted order references.

## Decision

Keep the existing single-database modular monolith. Use a full mutable-state revision with an
expected version, a pessimistic lock on the product row and the existing JPA version shared by
inventory operations. onHand is absolute and must be at least reserved. SKU and reserved are
server-owned. Inactive products are hidden publicly and reject new reservations; existing orders
retain snapshots and may finish. There is no delete endpoint.

Flush the revised entity, then insert actor-attributed before/after audit in the same transaction.
An insertion failure rolls back even the flushed version. Do not audit exact no-op states.
Preserve released migrations and introduce V6 with active=true for old products.

## Alternatives and consequences

- Relative stock deltas would need a durable idempotency ledger to make uncertain retries safe.
  The selected absolute revision plus precondition provides conflict detection for this bounded API.
  A repeated successful request returns conflict rather than replaying a stored result.
- Separate admin/inventory version columns would permit more concurrent edits, but would hide
  inventory changes from a stock reconciliation. One version deliberately requires re-reading.
- Relying only on optimistic flush failures could detect races, but checking under the inventory
  lock makes the reserved-stock invariant and revision conflict explicit.
- Event-only asynchronous audit would permit a committed change without its required history.
  This audit shares MySQL commit; Kafka is not required to edit the catalog.

The single row serializes concurrent changes and may be a hotspot. Multi-product orders still acquire
product locks in sorted order; an admin revision locks only one product and no order rows. Database
transient failures remain possible. Audit rows are append-only through the API, not protected from
privileged database operators. A warehouse ledger, retention and user lifecycle are separate work.
