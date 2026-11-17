# ADR 6 — Redis admission, transactional audit and truthful metrics

Status: implemented in CF-06; complete runtime verification pending.

Redis has a concrete admission-control role rather than speculative product caching.
A Lua script atomically checks/increments a fixed window and installs its TTL. Rejected calls
do not grow the counter. Default windows are 60 seconds: five login attempts, three registrations
per socket address, and sixty authorized writes per authenticated actor.
The filter runs after authorization; rejected/anonymous protected writes cannot spend actor quota.
Logout and reads are exempt so throttling or a Redis outage cannot prevent token revocation.

Identity is SHA-256 in Redis keys; no password, bearer token or raw socket address is retained.
This is pseudonymization, not strong anonymization of guessable addresses. Forwarding headers
are ignored and forward-header handling is disabled. A production reverse proxy requires an
explicit trusted-proxy design; the current socket policy is for loopback evaluation.

If protection is enabled but Redis is unavailable, protected requests return 503 before controller
execution; there is no permissive in-memory fallback. A denied allowance returns 429 with Retry-After.
The readiness admission indicator reports OUT_OF_SERVICE on Redis failure. Reads and protected
metrics remain accessible, so operators can inspect the outage. Feature-disabled host execution
does not require Redis. Compose enables protection with an independent generated local password.

Order audit starts at V5 and does not fabricate historical actors. New reservation and completion
records commit with order, stock, idempotency, business outbox and transport state. Retries and
terminal no-ops create no extra audit entry. System expiry has a null actor. Owner/ADMIN audit
reads and paginated ADMIN audit lists exclude credentials, prices and provider data.

Business transition counters increment only after commit. A later rollback must leave the audit
and counter unchanged. Metric recording failure after commit cannot convert a successful business
operation into a failed response. Queue gauges are observations, not atomic business snapshots.
Unavailable DB observations return NaN rather than a false zero. Delivery-attempt and bookkeeping
counters distinguish broker acknowledgement from a failed MySQL delivery mark; labels are bounded.

Fixed-window boundary bursts, per-address NAT sharing and Redis restart resetting ephemeral quotas
remain limits. No Redis cluster, production TLS/ACL, complete abuse defense, retention/cleanup,
load-capacity claim or public deployment is delivered. Durable audit and counters have different
retention/restart properties; audit is the historical record, process counters are not.

## Dependencies checked at implementation
Spring Boot 3.5.16 manages Spring Data Redis 3.5.13, Lettuce 6.6.0.RELEASE and Micrometer 1.15.12.
The explicit Redis 8.2.11 image follows the official 8.2 release line; compatibility is tested
against the actual container. No professional Redis experience is inferred from this project.

- https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html
- https://redis.io/docs/latest/develop/programmability/eval-intro/
- https://github.com/redis/redis/releases/tag/8.2.11
- https://redis.io/docs/latest/develop/tools/cli/
