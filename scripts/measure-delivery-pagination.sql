SET SESSION time_zone = '+00:00';

-- Copy the current table definition, including its indexes, into this session.
CREATE TEMPORARY TABLE cf_delivery_fixture LIKE message_dispatch;

INSERT INTO cf_delivery_fixture
  (id, kind, source_id, topic, payload, state, next_attempt_at, created_at)
WITH digits AS (
  SELECT 0 AS digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL
  SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL
  SELECT 8 UNION ALL SELECT 9
), fixture_numbers AS (
  SELECT a.digit + 10 * b.digit + 100 * c.digit + 1000 * d.digit + 10000 * e.digit AS n
  FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e
  WHERE e.digit < 5
)
SELECT LPAD(n, 12, '0'), 'REPLAY', LPAD(n, 12, '0'), 'synthetic.local', '{}',
       IF(MOD(n, 5) = 0, 'FAILED', 'SENT'),
       TIMESTAMPADD(SECOND, n DIV 100, '2026-01-01 00:00:00'),
       TIMESTAMPADD(SECOND, n DIV 100, '2026-01-01 00:00:00')
FROM fixture_numbers;

SELECT CONCAT('mysql=', VERSION(), ' total=', COUNT(*), ' failed=', SUM(state = 'FAILED'),
              ' distinct_timestamps=', COUNT(DISTINCT created_at))
FROM cf_delivery_fixture;

SELECT created_at, id INTO @anchor_time, @anchor_id
FROM cf_delivery_fixture WHERE state = 'FAILED'
ORDER BY created_at, id LIMIT 1 OFFSET 5024;

SELECT 'baseline_count';
EXPLAIN ANALYZE SELECT COUNT(*) FROM cf_delivery_fixture WHERE state = 'FAILED';

SELECT 'baseline_offset';
EXPLAIN ANALYZE
SELECT id,kind,state,attempts,retry_cycles,last_error_code,created_at,next_attempt_at,lease_until,sent_at
FROM cf_delivery_fixture WHERE state = 'FAILED'
ORDER BY created_at, id LIMIT 25 OFFSET 5025;

ALTER TABLE cf_delivery_fixture ADD INDEX ix_fixture_state_created_id (state, created_at, id);

SELECT 'candidate_count';
EXPLAIN ANALYZE SELECT COUNT(*) FROM cf_delivery_fixture WHERE state = 'FAILED';

SELECT 'candidate_offset';
EXPLAIN ANALYZE
SELECT id,kind,state,attempts,retry_cycles,last_error_code,created_at,next_attempt_at,lease_until,sent_at
FROM cf_delivery_fixture WHERE state = 'FAILED'
ORDER BY created_at, id LIMIT 25 OFFSET 5025;

SELECT 'candidate_cursor';
EXPLAIN ANALYZE
SELECT id,kind,state,attempts,retry_cycles,last_error_code,created_at,next_attempt_at,lease_until,sent_at
FROM cf_delivery_fixture WHERE state = 'FAILED'
  AND (created_at > @anchor_time OR (created_at = @anchor_time AND id > @anchor_id))
ORDER BY created_at, id LIMIT 25;

SELECT COUNT(*), SHA2(GROUP_CONCAT(id ORDER BY created_at, id), 256)
INTO @offset_size, @offset_hash
FROM (
  SELECT id, created_at FROM cf_delivery_fixture WHERE state = 'FAILED'
  ORDER BY created_at, id LIMIT 25 OFFSET 5025
) offset_page;

SELECT COUNT(*), SHA2(GROUP_CONCAT(id ORDER BY created_at, id), 256), MIN(created_at)
INTO @cursor_size, @cursor_hash, @cursor_first_time
FROM (
  SELECT id, created_at FROM cf_delivery_fixture WHERE state = 'FAILED'
    AND (created_at > @anchor_time OR (created_at = @anchor_time AND id > @anchor_id))
  ORDER BY created_at, id LIMIT 25
) cursor_page;

SELECT CONCAT('offset_matches_cursor=', IF(
  @offset_size = 25 AND @cursor_size = 25 AND @offset_hash = @cursor_hash
  AND @cursor_first_time = @anchor_time, 1, 0));
