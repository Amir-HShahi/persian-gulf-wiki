---
name: architecture-layering
description: Checks that a `core` change lands in the right package and layer — package-by-feature placement, class-suffix-to-layer match, and controller/service/repository boundary rules. Trigger on any file created, modified, or deleted under `core/`, per CLAUDE.md's Java layering rule. This governs layering, not style or tests — pair with code-style.md's naming rules and the relevant testing skill.
---

# Architecture layering (`core`)

Three checks, in order, for every file touched under `core/`.

## 1. Package-by-feature placement

- The file must live under `com.persiangulfwiki.core.<feature>.*`, never a generic
  cross-cutting layer package (no top-level `controllers/`, `services/`, `repositories/`).
  Existing features: `admin`, `audit`, `auth`, `dev`, `emailverification`, `expertreviewer`,
  `oauth2`, `password`, `user` — each with its own `controller/`, `service/`, `dto/`,
  `entity/`, `exception/`, `repository/` subpackages as needed (not every feature needs
  every subpackage — e.g. `mail` has no subpackages, `dev` has only `dto`).
- `common` holds only genuinely cross-feature primitives (`ApiResult`, `AuditableEntity`,
  the base `BadRequestException`/`ConflictException`/`NotFoundException`). Before adding
  something to `common`, check it's actually used by more than one feature — a single
  feature's concern belongs in that feature's package, not `common`.
- `config`, `security`, `utils`, `validation`, `web` are the accepted infra-style exceptions
  (framework wiring, cross-cutting security filters, generic helpers) — don't add a new
  top-level package outside this set or the feature list above without flagging it per
  CLAUDE.md's design-review rule.
- A new feature package is fine when the change introduces a genuinely new domain concept;
  it's not fine as a way to avoid picking the right existing feature package.

## 2. Class suffix matches its layer

code-style.md's suffix vocabulary is authoritative — verify the file is in the layer its
suffix implies:

| Suffix | Package | Layer |
|---|---|---|
| `Controller` | `<feature>.controller` | REST endpoints only |
| `Service` | `<feature>.service` | business logic |
| `Repository` | `<feature>.repository` | data access (Spring Data) |
| `*Request`/`*Response` | `<feature>.dto` | API DTOs, must be records |
| `Entity` (or bare domain name) | `<feature>.entity` | JPA entity |
| `Mapper` | `<feature>` (no mapper package exists yet — first one sets the pattern) | DTO↔entity conversion |
| `Exception` | `<feature>.exception` | custom exceptions |

A class in the wrong subpackage for its suffix (e.g. a `*Service` file sitting in
`controller/`) is a layering defect even if the code itself is correct.

## 3. Boundary rules

- **Controllers never touch repositories directly.** The only accepted exception in this
  codebase is `dev/DevTestUserController` — it bypasses the service layer on purpose (per
  testing.md) to reach otherwise-unreachable states like `enabled=false`. Don't cite that
  file as precedent for a non-dev controller; a normal `Controller` injecting a
  `*Repository` is a defect.
- **Services never build HTTP-facing payloads.** No `ProblemDetail`, `ResponseEntity`, or
  status-code decision belongs in a `*Service` class — that translation happens in exactly
  one place per api-conventions.md (a `@ControllerAdvice`, which doesn't exist yet in this
  codebase — see the `exception-handling` skill before adding one). A service throws a
  domain `*Exception`; it doesn't construct the error response.
- **Repositories stay Spring Data interfaces.** No business logic in a `*Repository` —
  query methods and `@Query` only; anything conditional/derived belongs in the `*Service`
  that calls it.
- **DTOs are inert.** A `*Request`/`*Response` record must not carry business logic or a
  reference to an entity type across the mapping boundary — mapping goes through a
  `Mapper` (or, until one exists for that feature, an explicit method on the `*Service`),
  not through the DTO itself.

## What NOT to do

- Don't create a new generic layer package (`controllers/`, `dto/` at the root) to "keep
  things organized" — that's the exact split-by-layer pattern code-style.md's Package
  structure rule forbids.
- Don't add a class to `common` just because it feels reusable — wait until a second
  feature actually needs it.
- Don't treat `DevTestUserController`'s repository access as license for any other
  controller to skip the service layer.
