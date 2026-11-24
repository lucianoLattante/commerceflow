# Local order contention and recovery

On disposable loopback Compose, set `CF_ALLOW_DISPOSABLE_LOAD=1` and export the synthetic bootstrap administrator variables used by that stack. Start the stack with `docker compose up -d --build --wait`. Run `python3 scripts/measure-order-contention.py`, then run the SQL inspection against the Compose MySQL service. The procedure also runs in GitHub Actions with disposable volumes.

```sh
docker compose exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' < scripts/inspect-contention.sql
python3 scripts/check-module-boundaries.py
```

Eight concurrent attempts sharing one key must produce one order and consume one unit. Eight independent keys compete for three units. The losing attempts fail with 409 and leave no reservation. After cancellation restores one unit, the same failed key can reserve it once and replay without another write. Existing integration tests exercise database fault injection and the broader outbox/inbox recovery path.

The benchmark records HTTP status distributions, wall time, request rate, nearest-rank p50/p95, runtime and fixture volume. It includes HTTP, application and client scheduling overhead, and the tiny sample makes high percentiles coarse. MySQL prints row counts and plans for scoped idempotency lookup and the due dispatch queue. Compare chosen keys with `uq_idempotency_customer_key` and `ix_dispatch_due` before changing an index. The latter orders by state and due time, while the ID tiebreaker may require additional sorting. Small tables can lead the optimizer to prefer scanning. No production capacity or comparative speedup is implied.

## Operator delivery pagination

After starting the disposable Compose stack, run the separate query experiment:

~~~sh
docker compose exec -T mysql sh -ec \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --raw --skip-column-names -u "$MYSQL_USER" "$MYSQL_DATABASE"' \
  < scripts/measure-delivery-pagination.sql
~~~

The experiment uses a session-local temporary table copied from the migrated delivery table definition. It populates 50,000 synthetic dispatches with 10,000 failures and 500 shared timestamps. It runs the failed-delivery page with size 25 after 5,025 records, plus the count query that supplies totalElements. It repeats the offset with an experimental index on state, creation time and ID, then looks for the same page using an exclusive creation time and ID cursor. The anchor is found separately to simulate a cursor returned by the preceding page. CI checks that both pages contain the same 25 IDs in order and that the first ID lies in the anchor's timestamp group.

Read the EXPLAIN ANALYZE sections for chosen access paths, actual rows, loops and iterator times. This experiment uses one synthetic state filter and timestamp distribution in a warm session. HTTP latency, concurrent changes, write overhead and other filter combinations are outside its scope. The temporary index disappears with the session. See the [MySQL EXPLAIN reference](https://dev.mysql.com/doc/refman/8.4/en/explain.html).
