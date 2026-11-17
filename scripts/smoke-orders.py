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
