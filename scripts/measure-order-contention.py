"""Small local workload. All credentials and records must belong to disposable Compose."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
import platform
import secrets
from threading import Barrier
from time import perf_counter
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import uuid4

BASE = "http://127.0.0.1:8080"
WORKERS = 8


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def call(method, path, data=None, token=None, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    request = Request(BASE + path, data=None if data is None else json.dumps(data).encode(),
                      headers=headers, method=method)
    before = perf_counter()
    try:
        response = build_opener(NoRedirects()).open(request, timeout=12)
    except HTTPError as rejected:
        response = rejected
    with response:
        status, raw = response.code, response.read()
    elapsed = (perf_counter() - before) * 1000
    return status, json.loads(raw) if raw else {}, elapsed


def require(result, status):
    actual, data, _ = result
    if status != actual:
        raise AssertionError(f"Expected HTTP {status}, got {actual}")
    return data


def wave(body, token, keys):
    start = Barrier(len(keys), timeout=8)

    def attempt(key):
        start.wait()
        return call("POST", "/api/orders", body, token, key)

    before = perf_counter()
    with ThreadPoolExecutor(max_workers=len(keys)) as workers:
        results = list(workers.map(attempt, keys))
    return results, perf_counter() - before


def describe(name, outcomes, wall_seconds):
    timings = sorted(result[2] for result in outcomes)
    counts = Counter(result[0] for result in outcomes)
    return {
        "phase": name, "requests": len(outcomes), "http_status": dict(sorted(counts.items())),
        "wall_seconds": round(wall_seconds, 3), "requests_per_second": round(len(outcomes) / wall_seconds, 2),
        "p50_ms_nearest_rank": round(timings[math.ceil(len(timings) * 0.50) - 1], 2),
        "p95_ms_nearest_rank": round(timings[math.ceil(len(timings) * 0.95) - 1], 2),
        "max_ms": round(timings[-1], 2),
    }


def main():
    if os.environ.get("CF_ALLOW_DISPOSABLE_LOAD") != "1":
        raise SystemExit("Set CF_ALLOW_DISPOSABLE_LOAD=1 only for an isolated disposable Compose database.")
    admin = require(call("POST", "/api/auth/token", {
        "email": os.environ["CF_BOOTSTRAP_ADMIN_EMAIL"], "password": os.environ["CF_BOOTSTRAP_ADMIN_PASSWORD"],
    }), 200)["accessToken"]
    suffix = uuid4().hex
    credentials = {"email": f"load-{suffix}@example.test", "password": secrets.token_urlsafe(24)}
    require(call("POST", "/api/auth/register", credentials), 201)
    token = require(call("POST", "/api/auth/token", credentials), 200)["accessToken"]

    def fixture(label, stock):
        product = require(call("POST", "/api/products", {
            "sku": f"LOAD-{label}-{suffix.upper()}", "name": f"Contention fixture {label}",
            "amount": "1.25", "currency": "USD", "stock": stock,
        }, admin), 201)
        return product["id"], {"items": [{"productId": product["id"], "quantity": 1}]}

    shared_product, shared_body = fixture("SHARED", 2)
    shared_key = "load-shared-" + suffix
    shared, elapsed = wave(shared_body, token, [shared_key] * WORKERS)
    print(json.dumps(describe("same_key", shared, elapsed)), flush=True)
    assert Counter(status for status, _, _ in shared) == {201: 1, 200: WORKERS - 1}
    assert len({body["id"] for _, body, _ in shared}) == 1
    assert require(call("GET", "/api/products/" + shared_product), 200)["available"] == 1

    stock = 3
    scarce_product, scarce_body = fixture("SCARCE", stock)
    keys = [f"load-scarce-{suffix}-{index}" for index in range(WORKERS)]
    scarce, elapsed = wave(scarce_body, token, keys)
    print(json.dumps(describe("scarce_stock", scarce, elapsed)), flush=True)
    assert Counter(status for status, _, _ in scarce) == {201: stock, 409: WORKERS - stock}
    assert require(call("GET", "/api/products/" + scarce_product), 200)["available"] == 0
    winner = next(body for status, body, _ in scarce if status == 201)
    lost_key = next(key for key, (status, _, _) in zip(keys, scarce) if status == 409)
    cancelled = require(call("POST", "/api/orders/" + winner["id"] + "/cancel", token=token), 200)
    assert cancelled["status"] == "CANCELLED"
    assert require(call("GET", "/api/products/" + scarce_product), 200)["available"] == 1
    retry = require(call("POST", "/api/orders", scarce_body, token, lost_key), 201)
    assert require(call("POST", "/api/orders", scarce_body, token, lost_key), 200)["id"] == retry["id"]
    assert require(call("GET", "/api/products/" + scarce_product), 200)["available"] == 0
    print(json.dumps({
        "python": platform.python_version(), "platform": platform.platform(), "cpu_count": os.cpu_count(),
        "workers": WORKERS, "products": 2, "customers": 1, "scenarios": 2,
        "reserved_orders_created": 5, "initial_scarce_stock": stock, "recovered_order": True,
    }), flush=True)


if __name__ == "__main__":
    main()
