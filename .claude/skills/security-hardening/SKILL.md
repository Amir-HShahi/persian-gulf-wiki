---
name: security-hardening
description: Prevents security defects while implementing auth/session, authorization, Spring Security config, secrets, cryptography, untrusted-input, dependency, or trust-boundary changes in `core` — the categories defined in security.md. Trigger before writing the code, per that rule's mandatory process. Do NOT trigger for a post-hoc review pass over already-written code — use the built-in `security-review` skill for that.
---

# Security hardening (`core`)

security.md defines *when* this skill fires (the trigger categories) and *that* it's mandatory. This skill defines *what to actually do*: the concrete checks for each category, grounded in how this codebase already does it. Match existing patterns below rather than inventing new ones.

## Auth/session flows

- Access tokens are JWT (`security/JwtService.java`, HS256), signed with `Keys.hmacShaKeyFor(Base64.decode(app.jwt.secret))` — `JWT_SECRET` must stay base64-encoded, ≥32 bytes. Don't switch algorithms or weaken key derivation without discussing it — HS256 with a strong shared secret is the accepted baseline here, not a placeholder to "improve."
- Refresh tokens are opaque random values (`TokenHasher.generateToken()`), never JWTs — only their SHA-256 hash is persisted (`TokenHasher.hash`, `RefreshTokenRepository`). Never store or log a raw refresh token. Any new token type follows the same shape: opaque value to the client, hash at rest.
- `AuthService.refresh` rotates the refresh token on every use and revokes-then-checks-reuse to detect a replayed token. If you add a new token-bearing flow, "revoke" must mean the same thing: the hash is invalidated server-side, not just that the client forgets it.
- `logout-all` calls `refreshTokenRepository.revokeAllActiveForUser` — a new revocation path must go through the same repository method, not a parallel one-off query.
- Password-reset/email-verify tokens are transported as a query parameter (`?token=`, `mail/EmailService.java`) by deliberate choice (commit 333ae99). Query-string tokens can leak via reverse-proxy/access logs, the `Referer` header on outbound requests from the landing page, and browser history — this is an accepted tradeoff because the tokens are single-use, short-TTL, and server-side-hashed. Don't silently move these back to a path segment or vice versa; if you touch this transport, keep the same bounded-blast-radius properties (single-use, short TTL, hashed at rest) or flag the tradeoff explicitly per CLAUDE.md's design-review rule.
- Session management is `SessionCreationPolicy.STATELESS` (`SecurityConfig`) — don't introduce server-side session state (e.g. `HttpSession` attributes) for a new flow; carry state in the token or a persisted entity instead.
- User enumeration is an accepted risk here, not a defect — this project's nature means account existence isn't sensitive. Error messages on login/register/password-reset/email-verify should be precise (e.g. "email already registered", "no account with that email") rather than deliberately vague. Don't genericize an error message to prevent enumeration; that's solving a problem this project doesn't have at the cost of a worse error for real users.

## Authorization/access control

- Ownership/role checks belong in the service layer, not the controller — controllers stay thin (routing + DTO mapping), so a check placed there is easy to skip when a second entry point to the same service method appears later.
- Fail closed: an unauthorized/unowned request must be rejected by default, not allowed unless explicitly denied. Prefer an explicit `@PreAuthorize`/ownership check over relying on the caller to have filtered results correctly.
- New `@PreAuthorize` expressions or ownership checks need integration coverage (per testing.md's mandatory-coverage rule) proving both the allowed and the denied path — see `integration-testing`.

## Spring Security config / filter chain

- Two chains exist: the main one and `dev/DevSecurityConfig.java` (`@Order(1)`, `@Profile("dev")`, `securityMatcher("/api/dev/**")`). A new dev-only or otherwise-scoped chain follows that pattern — a dedicated `@Order`d chain matched to its own path prefix, never appended to the main chain's `permitAll` list.
- CSRF is enabled (`CookieCsrfTokenRepository.withHttpOnlyFalse()`, `XorCsrfTokenRequestAttributeHandler`). It's ignored only for the handful of body-credentialed, no-ambient-cookie endpoints already listed in `SecurityConfig` (register, login, forgot-password, reset-password, verify-email). Don't add a new CSRF-ignored route without the same justification (the request must not rely on an ambient cookie for auth) — cookie-authenticated routes (`/api/auth/refresh`, `/api/auth/logout`) stay CSRF-protected.
- CORS origins come from `app.cors.allowed-origins` (`CorsConfig`) — a comma-split allowlist, not a wildcard. Adding a new frontend origin means adding it to that property (and `.env.example`/deploy config), not relaxing the matcher.
- Filter order matters: `jwtAuthenticationFilter` → `pendingPasswordSetupFilter` → `emailVerificationRequiredFilter`. A new stateful/auth-adjacent filter needs an explicit position in this chain, not an assumption about ordering.
- Any `@Component`-annotated `OncePerRequestFilter` is auto-registered by Boot to `/*` independent of the security chain it's meant for — `DevSecurityConfig` disables this via `FilterRegistrationBean.setEnabled(false)` for the dev-only filters. A new filter scoped to one chain needs the same explicit disable, or it silently runs on every request.

## Secrets and credentials

- Every secret is env-injected via `@Value`, never hardcoded — `JWT_SECRET`, `MAIL_PASSWORD`, `GOOGLE_CLIENT_SECRET`, `DB_PASSWORD` are the existing examples in `application.yaml`/`.env.example`.
- New secret-like properties follow CLAUDE.md's Configuration rule: add to `.env.example` in the same change as the yaml placeholder, keep names in sync, and diff `.env.example` against both yaml files before finishing.
- Never log a secret, token, or password — not even at `debug`. This includes raw JWTs and raw refresh tokens; log the user/token ID instead (matches code-style.md's logging rule).
- `application-dev.yaml` hardcoding empty mail credentials for local Mailpit is fine for `dev` only — don't let a `dev`-only credential shortcut leak into a non-dev profile's defaults.

## Cryptography

- Password hashing: `BCryptPasswordEncoder` (`security/PasswordEncoderConfig.java`), shared by both the local-password flow and `OAuth2Service.completeRegistration`. Use the injected `PasswordEncoder` bean for any new password-handling path — don't hash manually or introduce a second algorithm.
- Random values used for a security purpose (tokens, nonces) must come from a CSPRNG the way `TokenHasher.generateToken()` already does (32 random bytes, base64url) — never `java.util.Random` or a predictable seed.
- JWT signing key handling is described under Auth/session flows above — don't duplicate a second signing mechanism for a new token type without reusing `JwtService`.

## Untrusted input reaching a sensitive sink

- No raw outbound HTTP client (`RestTemplate`/`WebClient`/`HttpClient`) exists in `core` yet — the only outbound call is Spring Security's built-in OAuth2 client hitting Google's fixed, pre-configured endpoints, not a user-supplied URL. If you add the first case of the backend fetching a user-supplied URL, that's a new SSRF surface: validate/allowlist the target, block internal/link-local address ranges, and don't follow redirects blindly.
- No file upload handling (`MultipartFile`, `@RequestPart`) exists yet either. If you add the first one: validate content-type and size server-side (never trust the client-declared MIME type alone), never derive a storage path from user-supplied input without sanitizing it, and don't deserialize upload content as anything executable.
- Repository queries go through Spring Data method names/`@Query` with bound parameters — keep it that way; never concatenate user input into a query string.

## Dependency changes

- `.github/workflows/ci.yml`'s Trivy step (`scan-type: fs`, `scan-ref: core`, `severity: HIGH,CRITICAL`) already scans every PR and push to `master`, but `exit-code: 0` — it currently reports only, it does not fail the build. Don't treat a clean CI run as proof a new `pom.xml` dependency is safe; check the Trivy output yourself before merging.
- Before adding a dependency: prefer one already used elsewhere in `core` over introducing a new library for the same job (fewer supply-chain surfaces to track), and check it's actively maintained — an abandoned transitive dependency is exactly what Trivy's HIGH/CRITICAL scan is watching for.

## Trust boundary changes

- Checklist for any endpoint whose auth requirement changes: does the actual `SecurityFilterChain`/`securityMatcher` entry match what the controller's Javadoc/OpenAPI doc claims? A route becoming reachable without auth must be an explicit, reviewed line in `SecurityConfig`, never an accidental side effect of a matcher pattern change.
- Dev-only endpoints follow `dev/DevTestUserController.java`'s three-part pattern: `@Profile("dev")` on the controller (so the class doesn't exist outside that profile), a dedicated `@Order`d `securityMatcher`-scoped chain (`DevSecurityConfig`) rather than an entry in the main chain, and explicit `FilterRegistrationBean.setEnabled(false)` for any dev-only `@Component` filter that would otherwise auto-register globally. A new dev-only endpoint that skips any of these three leaks outside `dev`.
- A webhook endpoint (none exist yet) needs its own authentication (signature/shared-secret verification), not `permitAll()` — treat it as an authenticated route whose credential happens to be a header rather than a session/JWT.

## Verifying

Confirm the change actually holds by running the same class of check `regression-testing`/`integration-testing` would: hit the changed route in an `*IntegrationTests`/`*FlowIntegrationTests` class as both an authorized and an unauthorized caller and assert the status codes match intent (401 vs 403 vs 200, per api-conventions.md), not just that the happy path compiles.
