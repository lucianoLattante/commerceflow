# Case study — commerce foundation

## Current implemented decision

Start with a working catalog and identity boundary before introducing asynchronous order processing. This creates a reproducible foundation for testing later transaction and failure-recovery decisions.

Money is decimal and currency-aware; stock is bounded by domain and SQL constraints. Public readers cannot write catalog data, and registration cannot select ADMIN. Bearer tokens are digest-only and revocable. Flyway owns schema evolution.

## Evidence to demonstrate

Run unit tests, then mvn verify with Docker. Show a customer receiving 403 for product creation, the administrator creating a product, duplicate SKU returning 409, a revoked token receiving 401 and search treating % literally. Inspect schema constraints and the token table without exposing raw credentials.

The first local result is 33 passing unit tests. Use the actual CI run for database/container evidence. Do not present planned order or Kafka work as a completed capability.

## Tradeoffs to explain

- Why a shared-DB modular monolith precedes microservices.
- Why opaque tokens favor immediate revocation at the cost of a database lookup.
- Why a pure stock value object does not by itself prevent concurrent database overselling.
- Why application validation and database constraints protect different entry points.
- Why a successful compile differs from unit, MySQL integration and runtime-container verification.

## Interview questions

1. What transaction boundary will be needed when an order reserves multiple products?
2. How should an idempotency key be scoped, and what happens when its payload changes?
3. What crash window will an outbox address?
4. How would duplicate payment events affect stock if state transitions are not guarded?
5. Which tests must be added before claiming prevention of overselling?
6. What changes if authentication moves from explicit bearer headers to cookies?

Questions 1–5 partly concern the next increments; the current code does not implement those future answers.
