---
globs: ["**/*.java"]
---

# Security

## When this rule applies

Any of the following situations count as security-relevant. If a change touches one of these, it is in scope — not just changes to files literally named "security" or "auth":

- **Authentication/session flows** — login, registration, logout, password reset/verify, magic links, OAuth (Google), or anything that issues, validates, refreshes, or revokes a token (JWT, cookie, session).
- **Authorization/access control** — any code deciding who is allowed to see or do something: `@PreAuthorize`, role/permission checks, ownership checks (e.g. "is this the review's author").
- **Security filter chain / Spring Security config** — `SecurityFilterChain` beans, filters, CORS config, CSRF config.
- **Secrets and credentials** — code that reads, stores, transmits, logs, or generates a secret, password, API key, or token (includes anything touching `JWT_SECRET`, `MAIL_PASSWORD`, `GOOGLE_CLIENT_SECRET`, etc.).
- **Cryptography** — hashing, encryption, signing, or generating a random value used for a security purpose (session IDs, tokens, nonces, password hashes).
- **Untrusted input reaching a sensitive sink** — building a query from user input, handling file paths/uploads, making an outbound request to a user-supplied URL, deserializing user-supplied data.
- **Dependency changes** — adding or upgrading a dependency in `pom.xml` (this is why CI runs a Trivy scan on every PR, per @docs/BRANCHING.md).
- **Trust boundary changes** — an endpoint becoming reachable without authentication, a dev-only endpoint being exposed outside the `dev` profile, a webhook endpoint.

## Process (mandatory, overrides everything below)

Whenever a change falls into one of the situations above, invoke the `security-hardening` skill first, before writing or modifying the code.
