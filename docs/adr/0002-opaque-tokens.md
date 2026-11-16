# ADR 0002 — Short-lived opaque bearer credentials

Status: accepted for local evaluation.

Use 32 cryptographically random bytes encoded with base64url and a cf_ prefix. Store only SHA-256 digests and an expiry/user reference. Authentication reads the current user role/active flag on every request. Logout deletes the token record.

This avoids signing-key bootstrap and stale role claims while providing immediate revocation. The cost is an indexed database lookup per authenticated request and a dependency on database availability. JWT would reduce those reads but would require a separate revocation/stale-role policy.

Credentials are accepted only through an explicit Authorization: Bearer header. HTTP Basic, form login, authentication cookies and session authentication are disabled. CSRF is disabled under that narrow authentication model, not as a universal API rule. HTTPS is required before any non-local deployment.

Password hashing uses BCrypt(12), with an application limit of 12 characters minimum and 72 UTF-8 bytes maximum. Public registration assigns CUSTOMER on the server. Admin bootstrap is explicit, preserves existing credentials and cannot promote a pre-existing customer.

Known work: rate limiting, token cleanup, recovery, email verification, refresh flows and a full administrative lifecycle. Those omissions keep this increment scoped; it is not a production identity platform.
