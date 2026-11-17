# User administration

This increment adds bounded ADMIN role/activation changes. It does not implement password resets,
email verification, refresh tokens or a complete identity provider.

## API

Every route requires an ADMIN Bearer token. Application services also enforce that role.
Mutation revalidates the operator's persisted role and active state after acquiring the database
membership guard, so a waiting request cannot rely on an already-demoted administrator principal.

| Route | Behavior |
| --- | --- |
| GET /api/admin/users?role=CUSTOMER&active=false&page=0&size=20 | Filtered summaries, stable createdAt/id order |
| GET /api/admin/users/{id} | Current email, role, active flag, version and creation time |
| PUT /api/admin/users/{id} | Replace role/active state with a version precondition |
| GET /api/admin/users/{id}/audit?page=0&size=20 | Administrative changes, newest version first |

Page is 0..10000 and size 1..100. No view exposes passwords, password hashes, token digests or raw
credentials. The directory's email addresses are ADMIN-only personal data; do not log responses.
Required DTO fields cannot silently default an omitted active flag to false.

```http
PUT /api/admin/users/<user-id>
Authorization: Bearer <admin-token>
Content-Type: application/json

{"expectedVersion":0,"role":"CUSTOMER","active":false,"reason":"Access review"}
```

Read the current administrative view first. A stale version returns 409 user_state_conflict.
After an uncertain response or 503, re-read the state/history and reconcile; never blindly substitute
the newest version. Repeating an already-applied revision returns conflict, without a second effect.
An exact no-op with the current version returns 200 without token revocation, audit or version bump.
The reason is nonblank, at most 160 characters and must not contain credentials or sensitive details.

The API cannot assign IDs, email, password/hash, creation time or version. Unknown JSON properties are
ignored, not mapped onto entities. Registration still always creates CUSTOMER. There is no user delete.

## Commit boundary and concurrency

V7 adds app_users.version, a seeded singleton identity_admin_guard and user_admin_audit. Released V1-V6
are unchanged. Existing users start at version zero; existing tokens are preserved by migration.

A mutation uses one READ COMMITTED transaction:

1. Lock the membership guard, then revalidate the persisted operator.
2. Lock the target user and check expectedVersion.
3. If removing an active ADMIN, require at least one other active ADMIN.
4. Flush the role/active change and its new version.
5. Delete all target access tokens and insert before/after actor-attributed audit.
6. Commit everything together.

Concurrent self-disables cannot remove both remaining administrators. Concurrent cross-demotions
revalidate the waiting operator instead of trusting its old principal. The guard is a MySQL row,
shared across JVMs. Missing guard fails closed. Only administration/bootstrap take this guard;
ordinary authentication does not serialize every request on it.

Login locks the same user row until credential verification and token issuance commit. An earlier
login must finish before a state change can revoke its tokens; a later login reads the committed
active flag. This prevents token issuance from slipping past a concurrent revocation. Holding a
user lock through BCrypt intentionally serializes login attempts for that account; admission limits
apply when enabled. Transient database failures can still return retryable 503.

All actual state changes revoke target tokens, including promotion, demotion and reactivation.
A promoted user must authenticate again; old CUSTOMER tokens cannot acquire ADMIN privileges.
Reactivation never revives old tokens. A successful self-demotion/disable may return 200 to its
current request, but subsequent authentication with that token returns 401.

This does not cancel requests already authenticated before the change. Other modules enforce their
normal request/service contracts; only user-administration mutations revalidate under this guard.
Database operators and out-of-band writes are trusted and can bypass application invariants.

## Bootstrap and audit

Bootstrap joins the same guard and user-lock order. Concurrent startup for one email creates one
administrator. Existing credentials are never overwritten. An existing CUSTOMER or disabled ADMIN
fails bootstrap rather than being silently promoted/reactivated. Before demoting/disabling the
configured bootstrap account, point startup configuration to a remaining active administrator or
remove bootstrap configuration after initialization. Changing application state does not edit .env.

Audit contains target/operator IDs, reason/time, previous/new role/active/version and revoked-token
count, with no credential material. An audit insertion failure rolls back state, version and token
deletion. History starts with V7 administrative changes; registration/bootstrap/login are not
fabricated as earlier entries. Rows are append-only through the API, not tamper-proof against DB admins.
There is no retention/delete API in this increment.

See [ADR 8](adr/0008-identity-administration.md) and [executed verification](VERIFICATION.md).
