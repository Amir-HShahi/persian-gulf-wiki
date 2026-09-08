# Dev Users

Two populations, both on `@dev.local`, kept apart by the `e2e-` prefix: named fixtures a human logs into, and disposable accounts a test suite mints.

## Seeded Fixture Accounts

`DevUserSeeder` (`core/src/main/java/com/persiangulfwiki/core/dev/DevUserSeeder.java`) inserts a fixed cast of local accounts at startup, all with the password **`Dev-Password1!`**.

| Email | State | What it unblocks |
| --- | --- | --- |
| `contributor@dev.local` | verified, `CONTRIBUTOR` | the normal logged-in path |
| `unverified@dev.local` | `emailVerified = false` | the `EmailVerificationRequiredFilter` 403 path |
| `moderator@dev.local` | verified, `MODERATOR` | the moderation queue |
| `reviewer@dev.local` | verified, `EXPERT_REVIEWER` | review-specific UI |
| `admin@dev.local` | verified, `ADMIN` | admin user list, role grants |
| `disabled@dev.local` | `enabled = false` | the rejected-login path |

The unverified and disabled accounts are the point: neither can be produced by going through signup. Every account also holds `CONTRIBUTOR` — `RoleHierarchyConfig` only declares `ROLE_ADMIN > ROLE_MODERATOR`, so nothing else inherits contributor rights.

**Gating.** `@Profile("dev")` means the bean isn't registered outside dev at all — no flag to forget. A `seed.sql` under `db/migration/` would run in *every* environment; env-var gating (as `AdminBootstrapRunner` uses) is right for an operator-chosen admin password, wrong for credentials that are public and committed.

**Restarts.** Find-or-create by email, and roles are written only with a newly created user — so a role revoked by hand stays revoked. To start over: `docker compose down -v`.

**Ordering.** Both runners can create an `ADMIN`, and `AdminBootstrapRunner` skips itself once one exists — `@Order(1)` / `@Order(2)` keeps a configured `ADMIN_BOOTSTRAP_*` account from being silently suppressed.

## Disposable Test Users

The seeded accounts above are shared mutable state — fine for a human clicking around, a flake generator once E2E tests run in parallel (one test disables `moderator@dev.local` while another is logged in as it). `DevTestUserController` mints a fresh throwaway account per call instead, so each test owns its user.

```
POST /api/dev/test-users            # body optional; {} or omitted = verified, enabled CONTRIBUTOR
{ "roles": ["MODERATOR"], "emailVerified": true, "enabled": true }

201 → { userId, email: "e2e-3f9a1c8b2d4e@dev.local", username, password, roles }

DELETE /api/dev/test-users/{userId} # 204, or 404 if unknown *or* not an e2e- account
```

**Two gates, not one.** `@Profile("dev")` on the controller (the bean doesn't exist elsewhere) *plus* `DevSecurityConfig` — a profiled `@Order(1)` chain with `securityMatcher("/api/dev/**")`. Adding `/api/dev/**` to `SecurityConfig`'s `permitAll` list would have made the permit rule live in production; outside dev the dev chain isn't registered, so these paths fall through to `anyRequest().authenticated()` → 401.

**`DevSecurityConfig` also disables three filter auto-registrations.** `JwtAuthenticationFilter`, `PendingPasswordSetupFilter` and `EmailVerificationRequiredFilter` are `@Component`s, so Boot registers each as a servlet filter on `/*` independently of any chain. The main chain adds them early, so `OncePerRequestFilter` makes that copy a no-op — but on a chain that *doesn't* add them it becomes the first invocation, running after the `FilterChainProxy` with an anonymous token in place, and `EmailVerificationRequiredFilter` answers 403 `EMAIL_NOT_VERIFIED` to every mint call. The `FilterRegistrationBean`s exist only under dev, and the main chain still adds all three explicitly, so nothing else changes.

**Roles are literal** — `["MODERATOR"]` gives MODERATOR alone, no implied `CONTRIBUTOR`, unlike the seeder. Writes the `User` directly rather than going through `AuthService.register`, which would normalize the email, fire `sendVerification`, and make `enabled = false` unreachable.

**Plaintext password** exists only in the response body; the row stores the BCrypt hash and nothing logs it.

**Cleanup.** `DevTestUserSweeper` deletes `e2e-`-prefixed users past `app.dev.test-user-ttl-hours` (default 6, `DEV_TEST_USER_TTL_HOURS`), hourly at `:30`. The `e2e-` prefix — not the shared `@dev.local` domain — is what keeps the sweeper and the `DELETE` route off the seeded fixtures above. Every FK to `users(id)` is `ON DELETE CASCADE`/`SET NULL`, so removing the row takes its roles and tokens with it.
