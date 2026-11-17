# Catalog administration

This increment adds ADMIN product revision and inactive-product discovery. It does not implement user
administration or a warehouse ledger. Creation remains POST /api/products; SKU is immutable.

## Contract

All routes below require a current ADMIN Bearer token. Application services enforce the same role,
including reads, in addition to the HTTP policy. Redis admission covers authorized PUT requests when
enabled. Public catalog reads list active products only; an inactive product ID returns 404 there.
ADMIN can discover inactive products and read their state/history.

| Route | Purpose |
| --- | --- |
| GET /api/admin/catalog/products?active=false&page=0&size=20 | Discover inactive products; omit active for both states |
| GET /api/admin/catalog/products/{id} | Current SKU and state, including onHand, reserved and version |
| PUT /api/admin/catalog/products/{id} | Replace mutable state under an expected version |
| GET /api/admin/catalog/products/{id}/audit?page=0&size=20 | Newest product revisions first, ordered by version |

Pagination is bounded to page 0..10000 and size 1..100. Product lists sort by immutable SKU.
Names/reasons are nonblank and at most 160 characters. Amounts are positive two-decimal values in a
supported explicit currency. onHand is 0..1000000. Required nullable DTO fields prevent omissions
from silently becoming zero/false. Unknown JSON properties are ignored; they cannot assign SKU,
reserved inventory, version or an audit actor.

Read the administrative resource first, then submit its current version:

```http
PUT /api/admin/catalog/products/<product-id>
Authorization: Bearer <admin-token>
Content-Type: application/json

{"expectedVersion":3,"name":"Distributed Systems Notebook","amount":15.50,"currency":"USD","onHand":12,"active":false,"reason":"Manual inventory reconciliation"}
```

onHand is an **absolute balance that includes reserved units**, not an increment or available stock.
The caller cannot change reserved. A balance below reservations returns 409 product_conflict.
Deactivation prevents new reservations, but payment, cancellation and expiry of already reserved
orders still consume/release normally. Names, money and currency in existing order lines remain
their original snapshots, even if the catalog changes currency.

## Concurrency and retries

An update locks the same product row used by inventory, then checks expectedVersion. Reservations,
releases, payments and product edits share Hibernate's version field. A stale version returns
409 product_conflict without partial edits or an audit row. Two administrators editing the same
version cannot both commit. No-op state with a current version returns 200 without version increment
or audit insertion.

This API deliberately uses optimistic revision preconditions rather than a second idempotency-key
table. A repeated successful PUT with its old version returns 409; it never adds stock twice.
On an uncertain response, re-read the product and its history. Reconcile actual state before deciding
whether to send another change. Do not blindly replace expectedVersion with the newest value.
A 503 database_busy also requires this reconciliation. There is no automatic conflict merge.

## Atomic audit

V6 adds active=true for existing products and product_change_audit. Released V1-V5 are unchanged.
The product change is flushed, including its new version, before an audit insert in the **same MySQL
transaction**. If audit insertion fails, product fields and version roll back. Each entry retains actor,
reason, timestamp, before/after snapshots (including reservations) and a unique product/version pair.

The API exposes no mutation/deletion of audit rows. This is application-level append-only history,
not tamper-proof storage against a database administrator. History begins with V6 administrative
revisions; it does not invent prior changes or log every stock movement. Order stock changes continue
to appear through order-operation audit and order data. Reasons must contain operational context,
never credentials or sensitive personal data.

## Verification and limits

See [executed evidence](VERIFICATION.md). MySQL scenarios cover competing edits, reservation/payment
races, inactive basket rollback, retained order snapshots, completion of inactive reservations,
audit insertion failure/rollback, repeated/no-op revisions, HTTP role boundaries, required fields,
discovery/pagination and populated migration preservation. A loopback Compose smoke also exercises
administration over actual HTTP. Compilation alone is not evidence these scenarios ran.

No product deletion, SKU replacement, bulk revision, warehouse history/adjustment ledger, supplier
workflow, tax model, user administration, public deployment or load capacity is claimed.
