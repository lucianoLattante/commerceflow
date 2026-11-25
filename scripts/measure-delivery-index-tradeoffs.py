"""Compare operator page plans and index maintenance in disposable Compose MySQL."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from decimal import Decimal
import os
import re
from statistics import median
import subprocess
import sys
from uuid import uuid4


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


def measure_temporary():
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


@contextmanager
def persistent_fixture(indexed):
    table = f"cf_delivery_fixture_{uuid4().hex}"
    mysql(f"CREATE TABLE {table} LIKE message_dispatch;")
    try:
        seed = FIXTURE.replace("CREATE TEMPORARY TABLE cf_index_fixture LIKE message_dispatch;", "")
        counts = mysql(seed.replace("cf_index_fixture", table))
        if not re.fullmatch(
            r"mysql=\S+ total=50000 failed=10000 dlq=12500 failed_dlq=2500", counts.strip()
        ):
            raise RuntimeError("Unexpected persistent fixture volume")
        if indexed:
            mysql(f"ALTER TABLE {table} ADD INDEX ix_fixture_state_created_id (state, created_at, id);")
        yield table
    finally:
        mysql(f"DROP TABLE {table};")


def page_ids(table, predicate, offset=None):
    query = page(predicate, "id, created_at", offset).replace("cf_index_fixture", table)
    return f"SELECT GROUP_CONCAT(id ORDER BY created_at, id) FROM ({query}) visible_page;"


def verify_pages_between_commits():
    with persistent_fixture(indexed=True) as table:
        failed = "state = 'FAILED'"
        before_count, first_page = mysql(
            f"SELECT COUNT(*) FROM {table} WHERE {failed};\n{page_ids(table, failed)}"
        ).splitlines()
        first_ids = list(map(int, first_page.split(",")))
        if before_count != "10000" or first_ids != list(range(0, 125, 5)):
            raise RuntimeError("Unexpected original page")
        anchor = f"{first_ids[-1]:012d}"
        mysql(f"""
START TRANSACTION;
UPDATE {table} SET state = 'SENT' WHERE id IN ('000000000000', '000000000005');
UPDATE {table} SET state = 'FAILED' WHERE id = '000000000001';
COMMIT;
""")
        count, offset_ids, seek_ids = mysql(f"""
SELECT created_at, id INTO @anchor_time, @anchor_id FROM {table} WHERE id = '{anchor}';
SELECT COUNT(*) FROM {table} WHERE {failed};
{page_ids(table, failed, PAGE_SIZE)}
{page_ids(table, f"{failed} AND {CURSOR}")}
""").splitlines()
        offset_ids = list(map(int, offset_ids.split(",")))
        seek_ids = list(map(int, seek_ids.split(",")))
        if (count != "9999" or offset_ids != list(range(130, 255, 5))
                or seek_ids != list(range(125, 250, 5))):
            raise RuntimeError("Unexpected pages after committed filter changes")
        print("page_changes total_before=10000 total_after=9999 offset_first=130 cursor_first=125")


def commit_range(table, start_at, lower):
    output = mysql(f"""
DO SLEEP(GREATEST(0, {start_at} - UNIX_TIMESTAMP(SYSDATE(6))));
START TRANSACTION;
SET @started_at = UNIX_TIMESTAMP(SYSDATE(6));
UPDATE {table} FORCE INDEX (PRIMARY)
SET state = IF(state = 'FAILED', 'SENT', 'FAILED')
WHERE id >= '{lower:012d}' AND id < '{lower + UPDATE_ROWS:012d}';
SET @changed = ROW_COUNT();
COMMIT;
SELECT @changed, @started_at, UNIX_TIMESTAMP(SYSDATE(6));
""")
    rows, start, end = output.strip().split("\t")
    start, end = Decimal(start), Decimal(end)
    if int(rows) != UPDATE_ROWS or end <= start:
        raise RuntimeError("Unexpected committed update")
    return start, end


def measure_commits(indexed):
    with persistent_fixture(indexed) as table, ThreadPoolExecutor(max_workers=2) as writers:
        start_at = Decimal(mysql("SELECT UNIX_TIMESTAMP(SYSDATE(6)) + 3;").strip())
        futures = [writers.submit(commit_range, table, start_at, low) for low in (10_000, 20_000)]
        windows = [future.result() for future in futures]
        first_start = min(start for start, end in windows)
        last_end = max(end for start, end in windows)
        overlap = min(end for start, end in windows) - max(start for start, end in windows)
        failed = mysql(f"SELECT COUNT(*) FROM {table} WHERE state = 'FAILED';").strip()
        if failed != "22000":
            raise RuntimeError("The committed updates were not both visible to a separate session")
        return (last_end - first_start) * 1000, max(0, overlap) * 1000


def measure_durable():
    settings = mysql(
        "SELECT @@innodb_flush_log_at_trx_commit, @@log_bin, @@sync_binlog;"
    ).strip()
    flush, log_bin, sync_binlog = settings.split("\t")
    if flush != "1" or (log_bin == "1" and sync_binlog != "1"):
        raise RuntimeError("This experiment requires commit flushes and synchronous binary logs if enabled")
    print(f"commit_settings innodb_flush_log_at_trx_commit={flush} log_bin={log_bin} sync_binlog={sync_binlog}")
    verify_pages_between_commits()
    samples = {False: [], True: []}
    overlaps = {False: 0, True: 0}
    for trial in range(SAMPLES):
        for indexed in (False, True) if trial % 2 == 0 else (True, False):
            label = "indexed" if indexed else "baseline"
            elapsed_ms, overlap_ms = measure_commits(indexed)
            samples[indexed].append(elapsed_ms)
            overlaps[indexed] += overlap_ms > 0
            print(f"commit {label} trial={trial + 1} rows=20000 "
                  f"elapsed_ms={elapsed_ms:.3f} overlap_ms={overlap_ms:.3f}")
    for indexed, times in samples.items():
        label = "indexed" if indexed else "baseline"
        print(f"commit {label} median_ms={median(times):.3f} overlapping_trials={overlaps[indexed]}/{SAMPLES}")
    if any(count == 0 for count in overlaps.values()):
        raise RuntimeError("The experiment did not observe overlapping writers in both variants")


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--durable", action="store_true", help="measure commits and page changes between reads")
    args = cli.parse_args()
    if os.environ.get("CF_ALLOW_DISPOSABLE_LOAD") != "1":
        sys.exit("Start a disposable Compose stack and set CF_ALLOW_DISPOSABLE_LOAD=1.")
    if args.durable:
        measure_durable()
    else:
        measure_temporary()


if __name__ == "__main__":
    main()
