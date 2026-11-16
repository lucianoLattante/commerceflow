# ADR 0003 — Atomic orders, inventory and outbox

Status: implemented; verification results are tracked separately.

Use one MySQL database and one READ COMMITTED application transaction for the idempotency claim, inventory locks/reservation, immutable order/line snapshots and durable outbox event. This keeps the critical business invariant inside one commit boundary.

## Idempotency

A unique (customer_id, key_hash) constraint scopes the key to the authenticated account. Store SHA-256 key/request digests. The request fingerprint includes a version and canonical distinct product/quantity pairs sorted by UUID; line reordering is equivalent, duplicate product lines are rejected.

A MySQL INSERT ... ON DUPLICATE KEY UPDATE takes a row lock without deliberately raising a duplicate exception in a JPA transaction. A random request ID identifies the new claimant; affected-row counts are not used for ownership. The following locking read compares request hashes and returns a committed order on replay. A different payload returns 409. Failed transactions remove the claim along with their other effects.

The claim temporarily has a null order_id inside the transaction. It is completed after the order is flushed; the order_id foreign key then validates the link. A committed incomplete claim is treated as corruption, never as permission to reserve stock again.

## Concurrency and consistency

Inventory rows are locked in sorted UUID order with SELECT FOR UPDATE. Available stock is read and updated while those locks are held. Optimistic versioning and SQL stock constraints remain additional guards. Consistent ordering reduces the deadlock risk; it does not promise that all database deadlocks are impossible.

JpaTransactionManager and JdbcTemplate use the same DataSource/connection transaction. Inventory, claims and outbox adapters require an existing transaction. Outbox insertion failure must roll back the earlier JPA flush and the JDBC claim. Transient database failures return 503 with Retry-After; the caller retries with the same key. No automatic retry loop is introduced.

## Tradeoffs and limits

The protocol is MySQL-specific and serializes requests sharing a key or stock row. Keys and outbox rows have no cleanup/TTL yet. Replays return the existing order (201 on first creation, 200 on replay), never a second reservation/event. There is no exactly-once delivery claim: outbox publication is not implemented in this increment.

Orders currently remain RESERVED. Payment, cancellation and reservation-expiry transitions follow in CF-04. Kafka publishing/inbox/deduplication/retries/DLQ follow in CF-05.

References: [MySQL locking reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html), [duplicate-key locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html), [Spring JPA/JDBC participation](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html).

Order creation canonicalizes timestamps to MySQL TIMESTAMP(6) microsecond precision before persistence. This keeps the creation and replay representations stable; preserving nanoseconds only in the first in-memory response would break equality after a database round trip.
