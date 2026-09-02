# Review policy for automated Claude review

- **Important** = things that break production: logic bugs, incorrect data handling,
  migration hazards, security issues (auth/authz, secrets, injection), race conditions.
- **Nit** = style, naming, minor refactors — cap at 5 per review, don't repeat the same nit
  across multiple files.
- **Skip entirely**: generated code, `target/`, anything Checkstyle/SpotBugs/Trivy already
  catches once those are wired in, `docs/` content.
- Flag any deviation from `core/CLAUDE.md` (revision-based content model, Flyway-only schema
  changes, Testcontainers-based tests) as Important, not a nit — these are architectural
  invariants for this project, not style preferences.
