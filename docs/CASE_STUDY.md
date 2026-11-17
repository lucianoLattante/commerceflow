# Case study — atomic order reservation

## Implemented decision

CommerceFlow starts as a modular monolith. The order increment keeps request idempotency, stock reservation, order snapshots and outbox persistence inside one MySQL transaction.

The key is scoped to the authenticated account and stored as a digest. Canonical product/quantity input makes line reordering an equivalent retry. Changed meaningful input returns a conflict. Inventory rows are locked in a fixed order and quantities are validated while locks are held. Prices and names come from the server and are snapshotted.

A failed event insertion must not leave a reserved product or an order with no event. A committed event row is durable; it is not evidence of Kafka delivery.

## Evidence to demonstrate

Run mvn verify with Docker and inspect actual results. Demonstrate a new order, an equivalent retry returning the same order ID, a changed-payload 409, owner isolation and unchanged price snapshots after catalog edits.

The integration suite includes simultaneous same-key attempts, competing orders for limited stock, opposite input ordering and a temporary MySQL trigger rejecting outbox writes. It checks all affected tables and retries after the fault is removed. A migration test starts with V1 identity/catalog, adds a V2 order and verifies V3 preservation/backfilled expiry.

The CI additionally starts the containerized application and checks an order over network HTTP. It also approves/replays a simulated payment, cancels/replays another order and declines a third. Kafka remains planned, and a successful compile does not count as runtime execution.

## Interview questions

1. Why must the idempotency claim and stock writes share one commit boundary?
2. What differs between ordinary INSERT duplicate errors and the locking upsert used here?
3. Why does sorting locks reduce but not eliminate deadlock risk?
4. How does the outbox-failure test prove JPA/JDBC transaction participation?
5. Why are caller-supplied prices excluded from the request fingerprint?
6. What does a pending outbox row prove, and what must a future publisher/consumer add?
7. How do order locks and terminal guards prevent repeated stock consumption/release?
8. How should idempotency retention change once orders have a full lifecycle?

Questions 6 and 8 include future messaging/retention policies. The implemented [lifecycle ADR](adr/0004-order-lifecycle.md) explains question 7.

## Lifecycle discussion for an interview

Demonstrate the same payment request twice and inspect one completion event and one stock movement.
Race payment against cancellation, then explain why one terminal state wins under the order lock.
Expire a due reservation and show that an attempted payment returns EXPIRED without a simulated charge.

Explain the rollback trap: expiring stock and then throwing a runtime conflict in the same transaction
would undo expiry. This API returns the truthful terminal state instead.
Discuss why the clock is read after acquiring the order lock, why worker instances may select the
same candidate safely, and why an outbox row does not provide an external exactly-once guarantee.

Additional questions:
9. Why is a declined payment terminal in this simulator, and what would change with a real provider?
10. What happens if the database rejects the outbox insert after stock consumption was flushed?
11. How can a bounded scan recover after one order fails without rolling back the whole batch?
12. Why does an access-token refresh not extend the order reservation?
13. Which guarantees depend on synchronized clocks and the single MySQL transaction boundary?
14. How would an asynchronous payment provider require pending states, reconciliation and compensation?
