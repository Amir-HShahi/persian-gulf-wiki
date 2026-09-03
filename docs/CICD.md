# CI/CD Overview

## Flow

1. **Push to `master`** (via merged PR — direct pushes are blocked by the branch ruleset) → **CI** (`.github/workflows/ci.yml`) runs: `mvn verify`, coverage upload, Trivy vulnerability scan.
2. On CI success, **Deploy Staging** (`deploy-staging.yml`) triggers automatically via `workflow_run`. Builds the Docker image, pushes to GHCR tagged `:staging`, SSHes into the staging VPS over a Cloudflare Tunnel, runs `deploy.sh`.
3. **release-please** watches `master` and maintains a standing release PR. Merging it bumps the version, tags `vX.Y.Z`, and creates a GitHub Release.
4. The version tag push triggers **Deploy Production** (`deploy-production.yml`) — same build/deploy flow, tagged with the version number, but the job sits in the `production` GitHub Environment, which requires manual reviewer approval before it runs.

## Key pieces

- **Docker build context is the repo root**, not `core/` (`context: ., file: core/Dockerfile`). This is required so `git-commit-id-maven-plugin` can see `.git` during the build. The Dockerfile also explicitly copies `core/lombok.config` — Maven needs it to correctly wire `@Value` fields through Lombok's generated constructors.
- **GHCR package (`persian-gulf-wiki-core`) is public.** The VPS pulls anonymously; if it's ever made private again, the VPS needs its own `docker login` credentials or every deploy will fail with `unauthorized`.
- **SSH access is over a Cloudflare Tunnel**, not a public port. The runner installs `cloudflared`, then SSHes with `ProxyCommand="cloudflared access ssh --hostname <host>"`, authenticated via a Cloudflare Access **service token** (`CF_ACCESS_CLIENT_ID`/`SECRET` env vars). Host key checking is disabled — safe because Access already authenticates the tunnel before it reaches origin sshd.
- **`deploy.sh`** (lives on the VPS at `/opt/pgw/deploy.sh`, **not** synced automatically from the repo) pulls the new app image, runs `docker compose up -d app` (starts `postgres`/`redis` too if not already running — don't add `--no-deps`, that was a bug that skipped creating them entirely on a fresh box), then polls `/actuator/health/readiness` for up to 60s. On failure it dumps the last 100 log lines and exits nonzero, failing the GitHub Actions job.
- **`/actuator/health/**` must be `permitAll()`** in `SecurityConfig.java` — the health check curl is unauthenticated.
- **GitHub secrets per environment** (`staging`, `production`): `CF_ACCESS_CLIENT_ID`, `CF_ACCESS_CLIENT_SECRET`, `DEPLOY_SSH_PRIVATE_KEY`, and `STAGING_SSH_HOSTNAME`/`PROD_SSH_HOSTNAME`.
- **Branch ruleset on `master`**: PR required, `verify` CI check required, no force-push/delete, no admin bypass.

## Gotchas learned the hard way

- `gh run rerun` replays the workflow YAML **pinned at that run's original trigger time**, not current `master`. To test a workflow file change, push a new commit (or otherwise trigger a fresh `workflow_run`) rather than rerunning an old run.
- Cloudflare's Universal SSL only covers the zone plus **one level** of wildcard subdomain — multi-level hostnames (`api.foo.bar.example.com`) need a custom cert or a single-level naming scheme (this project uses `pgw-*.ravensandrunes.me`).
- GHCR package visibility for a **personal** (non-org) account can only be changed via the web UI (Package → Settings → Danger Zone) — there is no REST API for it.
- Hetzner VPS instances don't get a Primary IPv4 unless you explicitly purchase one (separate "Primary IPs" page at the **project** level, not the per-server Networking tab) — without it, outbound IPv4 (e.g. pulling from `ghcr.io`) fails with `ENETUNREACH`.
