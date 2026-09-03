# Branching & Flow

## Branches

- **`master`** — the only long-lived branch. Protected by a repo ruleset: PR required, `verify` CI check required, no force-push, no deletion, no admin bypass. Every push to it (via merged PR) auto-deploys to **staging**.
- **Feature/fix branches** — cut from `master`, named `<type>/<short-description>`:
  - `feat/…`, `fix/…`, `chore/…`, `docs/…`, `refactor/…`, `test/…`, `ci/…`
  - e.g. `fix/deploy-health-check`, `feat/moderation-queue`
- **Bot-owned branches** — never push to or rename these by hand:
  - `release-please--branches--master` (and its per-component variant) — release-please's standing release PR branch.
  - `dependabot/**` — Dependabot's update branches.

There is no separate `develop`/`staging` branch. Staging is just "whatever is on `master`."

## Flow

1. Branch off `master`, commit, open a PR.
2. `verify` (build + test + Trivy scan) must pass — enforced by the ruleset, can't merge without it.
3. Merge the PR (squash preferred, keeps `master` history linear) → CI reruns on `master` → **Deploy Staging** fires automatically.
4. Commit messages should follow **Conventional Commits** (`feat:`, `fix:`, `chore:`, etc.) — release-please parses these to decide the next version bump and changelog entries.
5. release-please keeps a standing PR titled `chore(master): release X.Y.Z` up to date with every conventional commit merged. **Merging that PR is the release act** — it bumps the version, tags `vX.Y.Z`, and creates a GitHub Release, which triggers **Deploy Production** (gated on manual reviewer approval).
6. Never push a `v*.*.*` tag by hand — always go through the release-please PR, or the tag/version and the changelog drift out of sync.

## PR expectations

- Keep PRs scoped to one concern — smaller diffs are easier to review and bisect if a staging deploy breaks.
- If a PR is purely infra/deploy config (Dockerfile, workflows, `deploy.sh`), say so in the description — those changes affect the *next* staging deploy immediately on merge, not just the app.
