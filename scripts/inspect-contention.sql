SELECT VERSION() AS mysql_version,
       (SELECT COUNT(*) FROM products) AS products,
       (SELECT COUNT(*) FROM customer_orders) AS orders,
       (SELECT COUNT(*) FROM idempotency_requests) AS idempotency_records,
       (SELECT COUNT(*) FROM message_dispatch) AS dispatch_records;

SELECT customer_id, key_hash INTO @customer_id, @key_hash
FROM idempotency_requests ORDER BY created_at DESC, request_id DESC LIMIT 1;

EXPLAIN FORMAT=JSON
SELECT order_id FROM idempotency_requests WHERE customer_id = @customer_id AND key_hash = @key_hash;

EXPLAIN FORMAT=JSON
SELECT id FROM message_dispatch
WHERE state = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6)
ORDER BY next_attempt_at, id LIMIT 10;
