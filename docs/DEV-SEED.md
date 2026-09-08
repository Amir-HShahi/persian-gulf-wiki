# Dev User Seeding

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
