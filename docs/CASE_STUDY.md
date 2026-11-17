# Case study — atomic order reservation

## Implemented decision

CommerceFlow starts as a modular monolith. The order increment keeps request idempotency, stock reservation, order snapshots and outbox persistence inside one MySQL transaction.

The key is scoped to the authenticated account and stored as a digest. Canonical product/quantity input makes line reordering an equivalent retry. Changed meaningful input returns a conflict. Inventory rows are locked in a fixed order and quantities are validated while locks are held. Prices and names come from the server and are snapshotted.

A failed event insertion must not leave a reserved product or an order with no event. A committed event row is durable; it is not evidence of Kafka delivery.

## Evidence to demonstrate

Run mvn verify with Docker and inspect actual results. Demonstrate a new order, an equivalent retry returning the same order ID, a changed-payload 409, owner isolation and unchanged price snapshots after catalog edits.

The integration suite includes simultaneous same-key attempts, competing orders for limited stock, opposite input ordering and a temporary MySQL trigger rejecting outbox writes. It checks all affected tables and retries after the fault is removed. A migration test starts with V1 identity/catalog, adds a V2 order and verifies V3 preservation/backfilled expiry.

The CI additionally starts the containerized application and checks an order over network HTTP. It also approves/replays a simulated payment, cancels/replays another order and declines a third. CF-05 adds actual Kafka delivery; a successful compile still does not count as runtime execution.

## Interview questions

1. Why must the idempotency claim and stock writes share one commit boundary?
2. What differs between ordinary INSERT duplicate errors and the locking upsert used here?
3. Why does sorting locks reduce but not eliminate deadlock risk?
4. How does the outbox-failure test prove JPA/JDBC transaction participation?
5. Why are caller-supplied prices excluded from the request fingerprint?
6. What do a pending outbox row, broker acknowledgement and committed inbox each prove?
7. How do order locks and terminal guards prevent repeated stock consumption/release?
8. How should idempotency retention change once orders have a full lifecycle?

Question 8 includes future retention policies; question 6 is demonstrated by CF-05. The implemented [lifecycle ADR](adr/0004-order-lifecycle.md) explains question 7.

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

## Kafka failure demonstration
[ADR 5](adr/0005-durable-kafka.md) separates business commit, broker acknowledgement and consumer
application. Show each observation rather than equate them. The integration suite injects a DB
failure after a real broker acknowledgement, then expires the lease. Inspect two broker records
carrying the same event but one committed inbox row. Next reject a projection write, inspect bounded
retries and durable recovery, repair the dependency and replay exact bytes with an audited ADMIN key.
Only present scenarios whose linked CI execution passed.

15. Why does producer idempotence not close the broker-ack/database-mark crash window?
16. What does a lease token fence, and why can a paused sender still publish a duplicate?
17. How do source sequence gates interact with a failed predecessor and operator replay?
18. Why must inbox insertion and projection update share one transaction?
19. Why persist a durable DLQ intent before advancing the original offset?
20. How do operator idempotency and replay budgets constrain accidental repeated recovery?

## Admission and observation demonstration
Pause the disposable Redis and show a protected write returning 503 without any order/stock/claim/audit
change. Reads, logout and authenticated metrics remain available. Restore Redis and retry the same key.
Then inject an outbox SQL failure after audit registration: audit and committed-transition counters
must both remain unchanged. Demonstrate an exhausted Redis counter without a TTL being repaired
without an extra allowance.

21. Why is a permissive process-local fallback unsuitable for this enabled admission policy?
22. How does one atomic Redis script avoid races between counter increment and expiry?
23. Which request identity is trustworthy without a configured reverse-proxy boundary?
24. Why must logout remain possible after write allowance exhaustion?
25. Why record business counters after commit, while send attempts have different semantics?
26. Why are durable audit, restartable counters and read-time gauges different kinds of evidence?
27. Why must a failed database observation be unknown rather than a healthy zero?

## Catalog revision demonstration
Read the administrative product version, reserve units and submit the old version: the change must
conflict. Read again, deactivate the product and complete the existing order at its original price.
Finally inject an audit insertion failure in the disposable test database and show fields/version
rolling back together. Use only the scenarios whose linked integration run passed.

28. Why share the product version between reservations and administrative stock reconciliation?
29. Why does a stale absolute revision return conflict instead of automatically merging?
30. How can an inactive product still require payment, release and expiry behavior?
31. Why flush before auditing, and why is a flushed write still reversible before commit?
32. What would relative stock deltas require to make retries safe?

## Identity administration demonstration
Use two ADMIN accounts to attempt simultaneous self-disables; exactly one should commit.
Then promote a CUSTOMER, show its old token rejected and authenticate again for ADMIN access.
In the disposable MySQL tests, hold credential verification during login and start a disable;
revocation must wait for issuance and remove its new token. Finally reject audit insertion and
show role/version and tokens rolling back together. Present only scenarios backed by a passed run.

33. Why does a count plus target-row lock fail to preserve the last administrator across two targets?
34. Why revalidate the operator after the membership guard rather than trust its authenticated principal?
35. How can token deletion race with credential verification and a later insert?
36. Why revoke credentials on promotion and reactivation as well as disable/demotion?
37. Which requests can still finish after revocation, and which guarantee does the guard actually provide?
38. Why should bootstrap reject a disabled configured account instead of silently restoring it?
