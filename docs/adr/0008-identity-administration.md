# ADR 8: serialize administrator membership and revoke target tokens atomically

Status: accepted for the user administration increment.

## Context

A count followed by an independent update can let two administrators disable the last two accounts.
Authentication precedes controller execution, so an operator waiting on a lock may already have lost
its role. Token deletion alone also races with a login that read active=true before revocation.

## Decision

Use a seeded MySQL singleton guard for membership mutations and bootstrap, then revalidate the operator
under READ COMMITTED. Lock the target user, require its expected version and preserve at least one
active administrator. Login locks that same user through password verification and token insertion.
After an actual state change, flush the user version, delete all target tokens and insert audit in
one transaction. A no-op creates no artificial version/audit and does not revoke credentials.

Require fresh authentication even for promotions and reactivation. No old token receives newly granted
privileges or becomes valid again after an inactive interval. Bootstrap preserves credentials and
rejects existing disabled/demoted configured accounts instead of undoing administration.

## Alternatives and consequences

- A process-local mutex would fail across application instances. The guard shares the authoritative DB.
- Locking only the target user would not protect the count across two distinct last-admin targets.
  The singleton deliberately serializes rare membership changes and may be an administrative hotspot.
- Relying only on per-request role reads would not revalidate an operator already waiting to mutate
  membership. Rechecking after the guard closes that specific race, without claiming request cancellation.
- Deleting tokens without serializing issuance can leave a newly issued credential after revocation.
  Holding the user lock through BCrypt adds bounded per-account contention; abuse limits and timeouts
  remain relevant. Authentication reads and ordinary business transactions do not take the singleton.
- A token epoch could avoid bulk deletion at larger scale, but introduces another version contract
  for every token. Digest-only short-lived tokens already exist; explicit deletion is sufficient here.
- An external identity provider is a separate deployment/integration decision, not required for this MVP.

Lock order is guard then target for membership/bootstrap; login takes only its user row. Audit is
transactional in MySQL and excludes credentials. Released migrations remain immutable; V7 preserves
existing users/tokens without fabricated history. Email/password recovery, MFA, refresh tokens,
token/audit retention, production identity infrastructure and in-flight request cancellation are deferred.
