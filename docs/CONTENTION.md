# Local order contention and recovery

On disposable loopback Compose, set `CF_ALLOW_DISPOSABLE_LOAD=1` and export the synthetic bootstrap administrator variables used by that stack. Start the stack with `docker compose up -d --build --wait`. Run `python3 scripts/measure-order-contention.py`, then run the SQL inspection against the Compose MySQL service. The procedure also runs in GitHub Actions with disposable volumes.

```sh
docker compose exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' < scripts/inspect-contention.sql
python3 scripts/check-module-boundaries.py
```

Eight concurrent attempts sharing one key must produce one order and consume one unit. Eight independent keys compete for three units. The losing attempts fail with 409 and leave no reservation. After cancellation restores one unit, the same failed key can reserve it once and replay without another write. Existing integration tests exercise database fault injection and the broader outbox/inbox recovery path.

The benchmark records HTTP status distributions, wall time, request rate, nearest-rank p50/p95, runtime and fixture volume. It includes HTTP, application and client scheduling overhead, and the tiny sample makes high percentiles coarse. MySQL prints row counts and plans for scoped idempotency lookup and the due dispatch queue. Compare chosen keys with `uq_idempotency_customer_key` and `ix_dispatch_due` before changing an index. The latter orders by state and due time, while the ID tiebreaker may require additional sorting. Small tables can lead the optimizer to prefer scanning. No production capacity or comparative speedup is implied.
