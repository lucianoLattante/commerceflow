"""Disposable loopback-only HTTP smoke flow. CI supplies synthetic bootstrap credentials."""
import json
import os
import secrets
import uuid
from decimal import Decimal
from urllib import request, error, parse

BASE = os.environ.get("SMOKE_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
if parse.urlparse(BASE).hostname not in ("127.0.0.1", "localhost"):
    raise RuntimeError("Smoke checks may only target a local test application")


def call(method, path, data=None, token=None, key=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    req = request.Request(BASE + path, data=None if data is None else json.dumps(data).encode(),
                          headers=headers, method=method)
    try:
        with request.urlopen(req, timeout=15) as response:
            status = response.status
            body = response.read()
    except error.HTTPError as response:
        status = response.code
        body = response.read()
    if status != expected:
        raise RuntimeError(f"Smoke {method} {path} returned {status}; expected {expected}")
    return json.loads(body, parse_float=Decimal) if body else None


admin = call("POST", "/api/auth/token", {
    "email": os.environ["CF_BOOTSTRAP_ADMIN_EMAIL"],
    "password": os.environ["CF_BOOTSTRAP_ADMIN_PASSWORD"],
})["accessToken"]
suffix = uuid.uuid4().hex
product = call("POST", "/api/products", {
    "sku": "SMOKE-" + suffix.upper(), "name": "Order smoke fixture",
    "amount": "12.34", "currency": "USD", "stock": 5,
}, token=admin, expected=201)
credentials = {"email": f"smoke-{suffix}@example.test", "password": secrets.token_urlsafe(24)}
customer = call("POST", "/api/auth/register", credentials, expected=201)
access = call("POST", "/api/auth/token", credentials)["accessToken"]
payload = {"items": [{"productId": product["id"], "quantity": 2}]}
key = "smoke-order-" + suffix
created = call("POST", "/api/orders", payload, token=access, key=key, expected=201)
replayed = call("POST", "/api/orders", payload, token=access, key=key)
assert created["id"] == replayed["id"]
assert created["customerId"] == customer["id"]
assert created["status"] == "RESERVED"
assert created["total"] == Decimal("24.68")
assert call("GET", "/api/products/" + product["id"])["available"] == 3
assert call("GET", "/api/orders/" + created["id"], token=access)["id"] == created["id"]
call("GET", "/api/orders/" + created["id"], expected=401)
paid = call("POST", "/api/orders/" + created["id"] + "/simulated-payment",
            {"decision": "APPROVED"}, token=access, key="smoke-payment-" + suffix)
assert paid["status"] == "PAID"
assert call("POST", "/api/orders/" + created["id"] + "/simulated-payment",
            {"decision": "APPROVED"}, token=access, key="smoke-payment-" + suffix) == paid
assert call("GET", "/api/products/" + product["id"])["available"] == 3
second = call("POST", "/api/orders", payload, token=access,
              key="smoke-cancel-" + suffix, expected=201)
assert call("GET", "/api/products/" + product["id"])["available"] == 1
cancelled = call("POST", "/api/orders/" + second["id"] + "/cancel", token=access)
assert cancelled["status"] == "CANCELLED"
assert call("POST", "/api/orders/" + second["id"] + "/cancel", token=access) == cancelled
assert call("GET", "/api/products/" + product["id"])["available"] == 3
call("POST", "/api/orders/" + second["id"] + "/simulated-payment",
     {"decision": "APPROVED"}, token=access, key="smoke-closed-" + suffix, expected=409)
third = call("POST", "/api/orders", payload, token=access,
             key="smoke-decline-" + suffix, expected=201)
declined = call("POST", "/api/orders/" + third["id"] + "/simulated-payment",
                {"decision": "DECLINED"}, token=access, key="smoke-decision-" + suffix)
assert declined["status"] == "PAYMENT_DECLINED"
assert call("GET", "/api/products/" + product["id"])["available"] == 3
print("Order HTTP smoke passed: ownership, totals, reservation, payment replay, cancel and decline.")


# Kafka is enabled by Compose. Observe a separate asynchronous projection over HTTP.
import time
for order_id, terminal in ((created["id"], "PAID"), (second["id"], "CANCELLED"),
                           (third["id"], "PAYMENT_DECLINED")):
    until = time.monotonic() + 30
    while True:
        projection = call("GET", "/api/orders/" + order_id + "/projection", token=access)
        if projection["status"] == terminal and projection["sequence"] == 2:
            break
        if time.monotonic() >= until:
            raise RuntimeError("Kafka projection did not reach the expected terminal state")
        time.sleep(0.25)
call("GET", "/api/admin/messaging/dead-letters", token=access, expected=403)
assert call("GET", "/api/admin/messaging/failures", token=admin) == []
print("Kafka HTTP smoke passed: paid, cancelled and declined projections reached sequence 2.")


# Audit is the durable history; verify one reservation and one terminal entry.
for order_id in (created["id"], second["id"], third["id"]):
    history = call("GET", "/api/orders/" + order_id + "/audit", token=access)
    assert [entry["sequence"] for entry in history] == [1, 2]
    assert all("password" not in entry and "token" not in entry for entry in history)
call("GET", "/api/admin/operations/audit", token=access, expected=403)


# Administrative revision keeps reservations server-owned and records one atomic change.
admin_path = "/api/admin/catalog/products/" + product["id"]
current_product = call("GET", admin_path, token=admin)
revision = {"expectedVersion": current_product["state"]["version"], "name": "Reviewed smoke fixture",
            "amount": "20.00", "currency": "USD", "onHand": 3, "active": False,
            "reason": "Disposable catalog administration smoke"}
call("PUT", admin_path, revision, token=access, expected=403)
changed = call("PUT", admin_path, revision, token=admin)
assert changed["state"]["onHand"] == 3 and changed["state"]["active"] is False
call("PUT", admin_path, revision, token=admin, expected=409)
call("GET", "/api/products/" + product["id"], expected=404)
assert call("GET", admin_path + "/audit", token=admin)["totalElements"] == 1
assert call("GET", "/api/orders/" + created["id"], token=access)["total"] == Decimal("24.68")
revision["expectedVersion"] = changed["state"]["version"]
revision["active"] = True
call("PUT", admin_path, revision, token=admin)
assert call("GET", "/api/products/" + product["id"])["available"] == 3
assert call("GET", admin_path + "/audit", token=admin)["totalElements"] == 2
print("Catalog HTTP smoke passed: ADMIN boundary, revisions, audit and original order totals.")

# Compose enables admission. Existing smoke logins share this loopback socket budget.
limited = False
for attempt in range(6):
    payload = json.dumps({"email": "missing-rate-fixture@example.test",
                          "password": "wrong-rate-test-fixture"}).encode()
    req = request.Request(BASE + "/api/auth/token", data=payload,
                          headers={"Content-Type": "application/json"}, method="POST")
    try:
        request.urlopen(req, timeout=15).close()
        raise RuntimeError("Invalid fixture credentials unexpectedly authenticated")
    except error.HTTPError as response:
        if response.code == 429:
            assert int(response.headers["Retry-After"]) >= 1
            assert json.loads(response.read())["code"] == "rate_limited"
            limited = True
            break
        assert response.code == 401
assert limited
assert call("GET", "/api/products/" + product["id"])["available"] == 3
print("Operability HTTP smoke passed: audit, authorization and Redis-backed admission.")
