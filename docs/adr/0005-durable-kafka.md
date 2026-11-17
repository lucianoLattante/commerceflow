# ADR 5 — at-least-once delivery and an idempotent projection

Status: implemented in CF-05; execution evidence pending.

Keep the business transaction independent of broker availability. The existing immutable
outbox_events row and a message_dispatch transport row are inserted in the same MySQL transaction.
V4 backfills transport rows for existing events without modifying V1–V3.

A dispatcher claims one due row using a committed 30 second lease and SKIP LOCKED. Kafka I/O runs
outside the DB transaction. Producer idempotence and acks=all reduce producer-session duplicates,
but the broker acknowledgement and MySQL SENT mark are separate resources. A crash in that window
must redeliver the same immutable event ID/payload. Completion/failure updates require the lease token.
Five attempts with 1/2/4/8 second delays end in durable FAILED state; abandoned fifth leases also fail.

Source events have explicit per-order sequence 1 (reserved), 2 (terminal). The terminal dispatch
cannot be claimed until its predecessor is marked SENT. Kafka records use the order UUID as key.
A permanently failed predecessor blocks its order until an administrator retries it.
No global order across unrelated orders or across operator replay is promised.

A consumer transaction inserts a unique event ID plus exact-wire SHA-256 digest and changes the
order projection atomically. A matching duplicate is a no-op; changed bytes under the same ID are poison.
The projection enforces owner consistency and contiguous sequence. Kafka offsets are acknowledged
only after this transaction commits. No consumer updates authoritative order state or inventory.

Transient/out-of-order consumer failures have two blocking retries (three attempts total).
Malformed messages bypass retries. Recovery stores the original broker coordinates and bounded
payload in message_dead_letters, and enqueues its DLQ transport in the same DB transaction.
Only then may the original offset advance. Kafka DLQ publication can retry independently of the
original consumer. This durable recovery outbox is deliberately used instead of pretending the
Kafka DLQ send and a database audit can share an atomic commit.

The ADMIN API lists metadata only and supports exact-payload replay and failed-publication retry.
Operator request keys are hashed, durable and scoped to target/action; retries of that API do not
enqueue another message. Each target permits at most three operator cycles. Replay preserves event
identity so the inbox still deduplicates already-applied data. There is no automatic DLQ loop.
Tombstones/oversized values retain a digest and coordinates but cannot be replayed through this API.

## Alternatives and limits

Kafka transactions alone cannot atomically commit MySQL side effects. XA is avoided.
Database-only polling would not demonstrate broker behavior, while a distributed payment saga
would add unrelated business states. The projection is an observable asynchronous use case.

The demo runs one Kafka KRaft broker with replication factor 1 and loopback-only PLAINTEXT.
Production ACL/TLS, multi-broker resilience, retention/cleanup policies and operator UI are not delivered.
A JVM pause longer than the lease can produce duplicates; the token fences DB updates, not Kafka I/O.
Clock synchronization remains required across publishers. An oldest failed reservation batch is
still a CF-04 operator concern. A complete advisory audit/load benchmark is not claimed.

## Compatibility checked

Spring Boot 3.5.16 manages Spring Kafka 3.3.16 and Kafka client 3.9.2; dependency versions are
inherited rather than overridden. Kafka broker image apache/kafka:4.1.1 is explicit and JVM based.
Real compatibility is exercised by CI, not inferred solely from the version table.

- https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html
- https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html
- https://java.testcontainers.org/modules/kafka/
- https://github.com/apache/kafka/blob/4.1.1/docker/jvm/Dockerfile
