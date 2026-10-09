# Frontend Handover Stack

There is no hosted backend. Frontend developers run the whole API locally from `deploy/frontend/`. The stack pulls the published API image, so it needs only Docker. No JDK, Go or repo build is involved.

## Run it

Only the `deploy/frontend/` folder is needed. A sparse checkout or a copy of that folder is enough.

```
cd deploy/frontend
docker compose up -d --wait
```

The first start takes about a minute while the database migrates.

- `--wait` was tested on Docker Compose 5.5.1, where the one-shot `minio-init` exiting with code 0 counts as success.
- Older Compose v2 releases may report that exit as a `--wait` failure. If yours does, drop `--wait` and check that `docker compose ps` shows `app` as healthy.

| What | Where |
|---|---|
| API | http://localhost:8080 |
| API reference (Scalar) | http://localhost:8080/docs/index.html |
| Mail inbox (verification and reset emails) | http://localhost:8025 |
| MinIO console (user `pgw-dev`, password `pgw-dev-secret`) | http://localhost:9001 |
| Postgres (`core` / `core` / `core`) | localhost:5432 |

- **Update to the newest backend:** `docker compose pull && docker compose up -d`.
- **Wipe all data:** `docker compose down -v`.

## Image and versions

- The image is `ghcr.io/amir-hshahi/persian-gulf-wiki-core`. It is public, so no login is needed.
- The default tag is `:staging`, which every merge to `master` rebuilds.
- To hold the backend still, set `PGW_IMAGE` to a full image reference with a version tag, either in a `.env` file next to the compose file or in the shell. Version tags exist only after the first release.

## Logging in

- **Seeded accounts:** the `dev` profile seeds fixed accounts on `@dev.local` for every role, all with password `Dev-Password1!`. See @docs/DEV-USERS.md.
- **Test users:** `POST /api/dev/test-users` mints a throwaway user per call.
- **Google sign-in does not work locally.** The OAuth client values are placeholders.
- **CSRF:** every state-changing call needs the CSRF handshake. Call `GET /api/auth/csrf` first, then send the encoded token as `X-XSRF-TOKEN`. The API reference describes the encoding. A raw cookie value is rejected with 403.
- **Cookies:** auth cookies are `Secure; SameSite=None`.
  - Chrome and Firefox accept them on `http://localhost`.
  - Safari does not, so use another browser.
  - The frontend must call the API as `localhost`, not `127.0.0.1`, because the cookie domain is `localhost`.

## Frontend origin

- CORS on the API and on MinIO allows only `FRONTEND_ORIGINS`. The default is `http://localhost:3000`.
- `FRONTEND_BASE_URL` is used for the links in emails. The default is also `http://localhost:3000`.
- If the dev server runs on another port, set both values in a `.env` file next to the compose file or in the shell, then run `docker compose up -d`.

## Media uploads

The Go worker in `submission-pipeline/` is not implemented yet, so the stack has no worker service.

- **What happens to a real upload:** the reserve, the browser PUT to MinIO and the complete call all work. The item then stays `PROCESSING` forever.
- **Testing gallery states:** use `POST /api/dev/test-media`. It mints gallery items directly in any processing or publication state. No file is stored; each item uses a 1×1 placeholder image.
- **When the worker exists:** add it here as a `worker` service, using the `MINIO_*`/`REDIS_*` mapping in @docs/MEDIA_PIPELINE.md, with `MINIO_ENDPOINT=minio:9000` and `REDIS_ADDR=redis:6379`.

## Safety

Everything binds to `127.0.0.1`. The `dev` profile exposes the unauthenticated `/api/dev/**` fixture endpoints, and every credential in the compose file is public. Never run this stack on a server or publish its ports on a network.

This stack and the repo-root `docker-compose.yml` use the same host ports, so stop one before starting the other.
