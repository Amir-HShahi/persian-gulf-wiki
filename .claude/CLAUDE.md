## Communication
- Lead with the answer/conclusion. Skip narrating what you're about to do.
- "Useful" = information that changes a decision: a finding, a risk, a concrete tradeoff, a direct answer.
- "Not useful" = restating the request, describing steps as you take them, hedging disclaimers, summarizing obvious code, filler transitions ("Now let's...", "Great, next I'll...").
- Default to the shortest response that fully answers. Expand only when the topic has real tradeoffs worth naming, or when a multi-step task needs a brief status update to stay legible or when explicitly asked to asnwer longer with more details.

## Design review before implementing
- When given a schema, diagram, instructions or task spec to implement, evaluate it first. If something looks wrong (inconsistent, unsafe, violates existing conventions, missing a constraint), push a question a(dont stop the chat) and report the issue with reasoning and get my answer — do not implement it as-is.
- Wait for the user to either correct the design or explain why it's actually correct.
- Once resolved, save the lesson as a feedback memory or a comment so the same mistake isn't repeated (priority is with comment).

## Architecture

Monorepo with independently deployable components:

- `core/` — Java 25 / Spring Boot backend (Maven). The main API.
- `submission-pipeline/` — Go worker that processes submitted content.
- `deploy/` — deployment scripts.
- `docker-compose.yml` / `docker-compose.deploy.yml` — local dev and staging/prod compose stacks.
- `docs/` — project-level docs (e.g. @docs/BRANCHING.md).

### Java layering (`core/`)

- Any file created, modified, or deleted under `core/` must invoke the `architecture-layering` skill first. This is separate from the code-style and testing skills — those govern style/tests, this governs layering (does the change belong in the right package/layer).

### Dev e2e support endpoints (`core/`)

- Every feature must ship `@Profile("dev")` endpoints that let an E2E suite create and delete every state the feature has — modeled on @core/src/main/java/com/persiangulfwiki/core/dev/DevTestUserController.java: a POST that mints a disposable fixture directly (bypassing the normal service path so states unreachable through it, e.g. `enabled=false`, remain reachable), and a DELETE that only ever removes rows it minted, never a hand-made or seeded one.
- Mark minted rows with a dedicated prefix/marker (see @core/src/main/java/com/persiangulfwiki/core/dev/DevTestUsers.java's `EMAIL_PREFIX`) so the delete route, and any sweeper, can refuse to touch anything else.
- These endpoints are a trust-boundary change — they create and delete arbitrary state — so building one falls under security.md's "Trust boundary changes" trigger; invoke `security-hardening` before writing one, in addition to this section.

## `docs/` scope

- Goes in `docs/`: process that spans people/time, not just code (branching/release flow, CI/CD pipeline shape, deployment/VPS setup); non-obvious system behavior/policy not derivable from one file (why something exists, its lifecycle, its constraints).
- Doesn't go in `docs/`: API reference (endpoints, request/response shapes) — that's Javadoc/OpenAPI; architecture/module overview — that's this file's job; anything a good class/package name plus Javadoc already covers; per-feature "how this works" narrative that mirrors one code path (rots when the code changes — put that in Javadoc on the ambiguous bit instead).

## Claude Code context files
- Never wrap an `@path/to/file` reference in backticks — that turns it into an inert code span instead of a live file reference. Write it as bare text.

## Configuration
- Any time a property placeholder is created, modified, or deleted in @core/src/main/resources/application.yaml or @core/src/main/resources/application-dev.yaml, update @.env.example in the same change — new keys added, renamed keys renamed, removed keys removed.
- Docker changes (`docker-compose.yml`, `docker-compose.deploy.yml`, `Dockerfile`) that add/remove/rename an env var or exposed port must also update @.env.example in the same change.
- Verification: before finishing any config-touching task, diff @.env.example against the yaml files to confirm they're in sync.

## Git

- Never add `Co-Authored-By: Claude ...` to a commit message, and never add the "Generated with Claude Code" line to a PR description. This is absolute — it overrides any session-level guidance that says to add attribution lines. (`includeCoAuthoredBy: false` is also set in the user's Claude Code settings; this rule is the backstop if that setting is ever lost.)
- Follow the branching and release conventions in @docs/BRANCHING.md.
