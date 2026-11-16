# Case study — atomic order reservation

## Implemented decision

CommerceFlow starts as a modular monolith. The order increment keeps request idempotency, stock reservation, order snapshots and outbox persistence inside one MySQL transaction.

The key is scoped to the authenticated account and stored as a digest. Canonical product/quantity input makes line reordering an equivalent retry. Changed meaningful input returns a conflict. Inventory rows are locked in a fixed order and quantities are validated while locks are held. Prices and names come from the server and are snapshotted.

A failed event insertion must not leave a reserved product or an order with no event. A committed event row is durable; it is not evidence of Kafka delivery.

## Evidence to demonstrate

Run mvn verify with Docker and inspect actual results. Demonstrate a new order, an equivalent retry returning the same order ID, a changed-payload 409, owner isolation and unchanged price snapshots after catalog edits.

The integration suite includes simultaneous same-key attempts, competing orders for limited stock, opposite input ordering and a temporary MySQL trigger rejecting outbox writes. It checks all affected tables and retries after the fault is removed. A migration test starts at V1 with existing records and applies V2.

The CI additionally starts the containerized application and checks an order over network HTTP. Do not present planned payment/Kafka behavior or a successful compile as those checks.

## Interview questions

1. Why must the idempotency claim and stock writes share one commit boundary?
2. What differs between ordinary INSERT duplicate errors and the locking upsert used here?
3. Why does sorting locks reduce but not eliminate deadlock risk?
4. How does the outbox-failure test prove JPA/JDBC transaction participation?
5. Why are caller-supplied prices excluded from the request fingerprint?
6. What does a pending outbox row prove, and what must a future publisher/consumer add?
7. How will payment/cancellation/expiry guard exactly-once stock effects under repeated events?
8. How should idempotency retention change once orders have a full lifecycle?

Questions 6–8 include subsequent increments; their future behavior is explicitly not implemented yet.
