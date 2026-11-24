"""Compare operator page plans and index maintenance in disposable Compose MySQL."""

import os
import re
from statistics import median
import subprocess
import sys


PAGE_SIZE = 25
OFFSET = 1002
SAMPLES = 3
UPDATE_ROWS = 10_000
FILTERS = {
    "unfiltered": "TRUE",
    "kind-only": "kind = 'DLQ'",
    "state-and-kind": "state = 'FAILED' AND kind = 'DLQ'",
}
COLUMNS = (
    "id, kind, state, attempts, retry_cycles, last_error_code, "
    "created_at, next_attempt_at, lease_until, sent_at"
)
CURSOR = "(created_at > @anchor_time OR (created_at = @anchor_time AND id > @anchor_id))"
FIXTURE = """
SET SESSION time_zone = '+00:00';
CREATE TEMPORARY TABLE cf_index_fixture LIKE message_dispatch;
INSERT INTO cf_index_fixture
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
SELECT LPAD(n, 12, '0'), IF(MOD(n DIV 5, 4) = 0, 'DLQ', 'REPLAY'),
       LPAD(n, 12, '0'), 'synthetic.local', '{}',
       IF(MOD(n, 5) = 0, 'FAILED', 'SENT'),
       TIMESTAMPADD(SECOND, n DIV 100, '2026-01-01 00:00:00'),
       TIMESTAMPADD(SECOND, n DIV 100, '2026-01-01 00:00:00')
FROM fixture_numbers;
SELECT CONCAT('mysql=', VERSION(), ' total=', COUNT(*),
              ' failed=', SUM(state = 'FAILED'), ' dlq=', SUM(kind = 'DLQ'),
              ' failed_dlq=', SUM(state = 'FAILED' AND kind = 'DLQ'))
FROM cf_index_fixture;
"""


def page(predicate, columns=COLUMNS, offset=None):
    suffix = f" OFFSET {offset}" if offset is not None else ""
    return (
        f"SELECT {columns} FROM cf_index_fixture WHERE {predicate} "
        f"ORDER BY created_at, id LIMIT {PAGE_SIZE}{suffix}"
    )


def write_sample(label):
    return f"""
START TRANSACTION;
SET @started_at = SYSDATE(6);
UPDATE cf_index_fixture FORCE INDEX (PRIMARY)
SET state = IF(state = 'FAILED', 'SENT', 'FAILED')
WHERE id >= '000000010000' AND id < '000000020000';
SELECT CONCAT('write {label} rows=', ROW_COUNT(),
              ' microseconds=', TIMESTAMPDIFF(MICROSECOND, @started_at, SYSDATE(6)));
ROLLBACK;
SELECT CONCAT('restored {label} failed=', SUM(state = 'FAILED')) FROM cf_index_fixture;
"""


def read_scenarios(indexed):
    statements = []
    for label, predicate in FILTERS.items():
        prefix = "indexed" if indexed else "baseline"
        statements.extend([
            f"SELECT 'plan {label} {prefix}-offset';",
            f"EXPLAIN ANALYZE {page(predicate, offset=OFFSET)};",
        ])
        if not indexed:
            continue
        statements.append(
            "SELECT created_at, id INTO @anchor_time, @anchor_id FROM cf_index_fixture "
            f"WHERE {predicate} ORDER BY created_at, id LIMIT 1 OFFSET {OFFSET - 1};"
        )
        seek = f"{predicate} AND {CURSOR}"
        statements.extend([
            f"SELECT 'plan {label} indexed-cursor';",
            f"EXPLAIN ANALYZE {page(seek)};",
            f"""
SELECT COUNT(*), GROUP_CONCAT(id ORDER BY created_at, id) INTO @offset_size, @offset_ids
FROM ({page(predicate, 'id, created_at', OFFSET)}) offset_page;
SELECT COUNT(*), GROUP_CONCAT(id ORDER BY created_at, id), MIN(created_at)
INTO @cursor_size, @cursor_ids, @first_time
FROM ({page(seek, 'id, created_at')}) cursor_page;
SELECT CONCAT('equal {label}=', IF(
  @offset_size = {PAGE_SIZE} AND @cursor_size = {PAGE_SIZE}
  AND @offset_ids = @cursor_ids AND @first_time = @anchor_time, 1, 0));
""",
        ])
    return "\n".join(statements)


def mysql(sql):
    command = [
        "docker", "compose", "exec", "-T", "mysql", "sh", "-ec",
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --raw --skip-column-names '
        '-u "$MYSQL_USER" "$MYSQL_DATABASE"',
    ]
    result = subprocess.run(
        command, input=sql, text=True, encoding="utf-8", stdout=subprocess.PIPE,
        timeout=90, check=True,
    )
    return result.stdout


def main():
    if os.environ.get("CF_ALLOW_DISPOSABLE_LOAD") != "1":
        sys.exit("Start a disposable Compose stack and set CF_ALLOW_DISPOSABLE_LOAD=1.")

    baseline = read_scenarios(indexed=False)
    indexed = read_scenarios(indexed=True)
    samples = {"baseline": [], "indexed": []}
    for sample in range(SAMPLES):
        output = mysql(
            FIXTURE + (baseline if sample == 0 else "") + write_sample("baseline")
            + "\nALTER TABLE cf_index_fixture ADD INDEX ix_fixture_state_created_id (state, created_at, id);\n"
            + (indexed if sample == 0 else "") + write_sample("indexed")
        )
        lines = output.splitlines()
        if not any(re.fullmatch(
            r"mysql=\S+ total=50000 failed=10000 dlq=12500 failed_dlq=2500", line
        ) for line in lines):
            raise RuntimeError("Unexpected synthetic data volume")
        if sample == 0 and any(f"equal {label}=1" not in lines for label in FILTERS):
            raise RuntimeError("The cursor and offset did not return the same tied page")
        print(f"sample {sample + 1}/{SAMPLES}")
        print(output.rstrip())

        for label, times in samples.items():
            matches = re.findall(rf"^write {label} rows=(\d+) microseconds=(\d+)$", output, re.M)
            if (len(matches) != 1 or int(matches[0][0]) != UPDATE_ROWS
                    or int(matches[0][1]) <= 0 or f"restored {label} failed=10000" not in lines):
                raise RuntimeError(f"Unexpected {label} write measurement")
            times.append(int(matches[0][1]))
    for label, times in samples.items():
        print(f"write {label} median_ms={median(times) / 1000:.3f}")


if __name__ == "__main__":
    main()
