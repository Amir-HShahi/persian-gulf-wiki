# New VPS Setup (staging or production)

Steps to bring up a fresh VPS so it can receive deploys from `deploy-staging.yml` / `deploy-production.yml`. Do this once per VPS (staging is done; production is not).

## 1. Provision

- Hetzner Cloud (or similar). **Buy a Primary IPv4** for it — under the *project's* "Primary IPs" page, not the per-server Networking tab. Without it, the server only has IPv6 and outbound pulls to `ghcr.io` fail with `ENETUNREACH`.
- Install Docker + Docker Compose plugin.

## 2. Cloudflare Tunnel

- Create a tunnel in the Cloudflare dashboard (Zero Trust → Networks → Tunnels), remotely managed (`config_src: cloudflare`).
- Add a public hostname route pointing at `ssh://localhost:22` on the VPS.
- Install `cloudflared` on the VPS, fetch the tunnel's connector token via API or dashboard, write it to `/etc/cloudflared/token`, then `cloudflared service install` (or `systemctl enable --now cloudflared` if the unit is already templated).
- **Verify the token file byte-for-byte** (`sha256sum`) before restarting — a single dropped/corrupted character (easy to do pasting into a web console) produces `Unauthorized: Invalid tunnel secret` errors that look like a connectivity problem but aren't.
- Confirm health via `GET /accounts/{account_id}/cfd_tunnel/{tunnel_id}` — `status: "healthy"` with active connections listed.

## 3. Cloudflare Access (for SSH)

- Create a Zero Trust **Access application**, type `self_hosted`, domain = the tunnel's SSH hostname.
- Add a **service token** policy (non-identity) so CI can authenticate headlessly — browser-based login won't work for GitHub Actions.
- Store the service token's Client ID/Secret as `CF_ACCESS_CLIENT_ID` / `CF_ACCESS_CLIENT_SECRET` in the matching GitHub Environment.
- **Client ID and Client Secret are paired** — if you rotate one, rotate both together, or auth silently fails with 403.

## 4. SSH key for deploys

- Generate a dedicated ed25519 keypair for CI (don't reuse a personal key).
- Public key → VPS's `deploy` user `~/.ssh/authorized_keys`.
- Private key → GitHub Environment secret `DEPLOY_SSH_PRIVATE_KEY`.
- Firewall: only allow SSH over the tunnel — don't expose port 22 publicly. Lock down everything except what's needed (80/443 if serving direct, otherwise nothing public-facing at all since the tunnel handles ingress).

## 5. App directory

On the VPS, create `/opt/pgw/` containing:
- `docker-compose.deploy.yml` — copy from repo root. **This file is not synced automatically** — if it changes in the repo, manually update it on the VPS too.
- `deploy.sh` — copy from `deploy/deploy.sh` in the repo. Same caveat: not auto-synced.
- `.env` — all the secrets `docker-compose.deploy.yml` interpolates (`DB_PASSWORD`, `MAIL_*`, `GOOGLE_CLIENT_*`, `JWT_SECRET`, `FRONTEND_*`, `ADMIN_BOOTSTRAP_*`). Never commit this file anywhere. On **staging** it also carries the Mailpit switches — see §8.

Paste multi-line files into the console carefully — Hetzner's web console (and similar VNC-style consoles) can corrupt heredoc pastes. If a script throws a bash syntax error right after pasting, that's the likely cause: re-write it via `base64 -d` from a single-line encoded blob instead, and verify with `sha256sum`.

## 6. First bring-up

- `cd /opt/pgw && docker compose -f docker-compose.deploy.yml up -d` once manually to confirm `postgres`/`redis`/`app` all start and the app can reach the database.
- Add `STAGING_SSH_HOSTNAME` or `PROD_SSH_HOSTNAME` (the tunnel's SSH hostname) to the matching GitHub Environment secrets.
- Trigger a real deploy from GitHub Actions and confirm `/actuator/health/readiness` returns 200 (requires it to be `permitAll()` in `SecurityConfig.java`).

## 7. Production-specific

- The `production` GitHub Environment should have a required reviewer configured (Settings → Environments → production → protection rules) so `deploy-production.yml` pauses for manual approval before running.

## 8. Staging-only: Mailpit

Staging sends mail to a Mailpit container instead of a real provider, so verification/reset emails land in a web inbox at `pgw-staging-mail.ravensandrunes.me`. **Never on production.**

In `/opt/pgw/.env`:

```
COMPOSE_PROFILES=staging
MAIL_HOST=mailpit
MAIL_PORT=1025
SPRING_PROFILES_ACTIVE=dev
```

- `COMPOSE_PROFILES=staging` is what activates the service (`mailpit` sits under `profiles: [staging]` in the shared compose file); production omits it and never creates the container.
- `SPRING_PROFILES_ACTIVE=dev` loads `application-dev.yaml`, which disables SMTP auth/STARTTLS — Mailpit has neither, and without it every send fails with `530 Error: authentication Required`.
- SMTP 1025 is intentionally unpublished (open relay on a public IP); the UI binds to `127.0.0.1:8025`, tunnel-only.

Start it once by hand — `deploy.sh` won't, since Mailpit is deliberately not in `app`'s `depends_on` (Compose auto-enables profiled dependencies, which would start it on production too):

```
cd /opt/pgw && docker compose -f docker-compose.deploy.yml --profile staging up -d mailpit
```

Cloudflare (done for the current staging box; repeat per new one): proxied `CNAME pgw-staging-mail` → `<staging-tunnel-id>.cfargotunnel.com`, tunnel ingress → `http://localhost:8025` before the 404 catch-all, and a `self_hosted` Access app with an email allowlist (one-time PIN — no other IdP configured). **Create the Access app first** — between DNS and policy the inbox's live verification links are exposed. `curl -I` the hostname: a `302` to `cloudflareaccess.com` means the gate is on; a `200` means it isn't.
