# Core ↔ submission-pipeline

How core (the Spring API) and the Go worker in `submission-pipeline/` work together: gallery media, the advisory checks on article revisions, and article search. The worker's side of the contract is fixed and specified in `submission-pipeline/README.md` (job payloads, result shapes, reason codes) and `submission-pipeline/CORE_CHANGES.md`. This page covers the policy and lifecycle on core's side that no single class shows. Core never edits `submission-pipeline/`.

## 1. Postgres is the only channel

```
core ──INSERT, in the same transaction as its own change──▶ jobs ◀── worker claims, runs, writes result
core ◀── polls every app.job-queue.result-poll-interval-seconds (5 s) for done/failed rows
worker ──every 30 s──▶ search_documents (built from the article tables)
core ──SELECT search_articles(...) / search_suggest(...) / search_did_you_mean(...)
```

- **The `jobs` table belongs to core's migrations.** `V37__create_jobs_queue.sql` is a byte-for-byte copy of `submission-pipeline/internal/queue/schema.sql`, so a fresh database has the table as soon as core has started once. The worker re-applies its own file on every start; it is idempotent, so the copy and the worker's apply agree. Never edit the copy, and never add a core migration that alters `jobs`.
- **The search index belongs to the worker.** It creates `search_documents` and the search functions once core's migrations exist, and may drop and rebuild them when their shape changes. Until it has run once, core answers every search with no results and logs one warning.
- **Enqueue is transactional.** A job row commits with the status change that needs it (an item moving to PROCESSING, a revision moving to PENDING) or not at all. Nothing is orphaned in either direction, which is why the Redis streams were removed. Redis remains for rate limits only.
- **Nothing is acknowledged.** Core's polls select rows whose owner is still waiting (an item in PROCESSING, a check in PENDING) and whose job is finished. Applying a result moves the owner on, so the row stops matching. A result is applied only while the owner still names that job id, so a late result from an earlier job never overwrites a newer one.
- **Worker output is checked, not trusted.** A result that is unreadable, of an unknown version or type, or fails validation (variant keys outside the item's own prefix, a malformed hash or placeholder, a non-http(s) URL) is treated as a failure and nothing from it is stored.

## 2. Job rows are temporary

The worker deletes `done` jobs 30 days after they finish and `failed` ones after 90 (`JOBS_KEEP_DONE` / `JOBS_KEEP_FAILED`). Core therefore copies everything it keeps into its own tables when it applies a result: an item's variants, placeholder, verified hash and attribution; a revision check's findings. The links from `article_media.processing_queue_job_id` and `revision_checks.job_id` to `jobs` are `ON DELETE SET NULL`, so the clean-up never fails on them.

## 3. Gallery items

| Kind | Job type | What happens |
|---|---|---|
| IMAGE | `image` | WebP variants (`w320` … `w1920`), placeholder, verified SHA-256, attribution. |
| PANORAMA_360 | `panorama` | The same, 2:1 enforced, variants `2k` / `4k` / `8k`. |
| VIDEO | none | **Queued nowhere**: the worker has no video handler, and a job of an unknown type would fail as a broken file. The item stays PROCESSING until the stuck-processing sweep fails it as `processing_timeout`, up to about 2 h, holding one of its uploader's pending slots and its bytes against their quota meanwhile. Chosen on 2026-11-07, knowing that cost, until a video handler exists. |

Accepted upload types for IMAGE and PANORAMA_360: JPEG, PNG, WebP, HEIC and AVIF (HEIC is the iPhone default; the worker decodes both).

Outcome of a finished job:

| Row | Item becomes |
|---|---|
| `done`, outcome `ok`, payload valid | READY; moderation tasks open for its pending metadata versions. |
| `done`, outcome `ok`, same verified hash already live in the article | FAILED `duplicate` |
| `done`, outcome `rejected` | FAILED with the worker's reason code if core knows it (`MediaFailureCode`), else the generic `rejected`. `object_missing` and `malformed_payload` land on `rejected` on purpose: they are not about the file. |
| `failed` | FAILED `processing_error` (the worker has already retried) |
| anything unreadable or invalid | FAILED `processing_error` |
| no result within `app.media.processing-timeout-minutes` (checked hourly) | FAILED `processing_timeout` |

**Attribution** is what the file's own metadata claims about its author and licence. Core stores it cleaned (single line, no control or bidi-override characters, 300 characters a field, licence URL http(s) only) and shows it only to moderators, beside what the uploader declared. It is a hint, never the item's licence.

### Where an item's files live, and when they go

| Object | Written by | Deleted |
|---|---|---|
| staging `{mediaId}` (the upload as sent) | the browser | by core once the item is READY (after the commit); with the item by the sweeps otherwise |
| media `pending/{mediaId}/…` (variants) | the worker | moved to `public/{mediaId}/…` on first approval; with the item by the sweeps otherwise |
| media `originals/{mediaId}/original.<ext>` | the worker | with the item, by the same sweeps: abandoned uploads, rejected items after `app.media.rejected-grace-days`, failed items after `app.media.failed-grace-days` |
| media `public/{mediaId}/…` | core, on approval | not yet (HIDE and removal are a later phase) |

The worker never deletes anything. `originals/` is private and must never be served: it keeps the camera's metadata, location included. It is kept for published items as the only source if a new size or format is ever needed.

## 4. Revision checks

Every article revision submit (the first one, and each resubmit after REQUEST_CHANGES) resets the revision's two `revision_checks` rows to PENDING and queues a `normalize` and a `links` job, in the submit's own transaction. The previous findings are dropped, since the text has changed.

- **Advisory only.** Nothing in moderation reads them; a check that fails or never finishes changes nothing about whether the revision can be decided. Nothing times a check out.
- **Who sees them:** moderators, with the revision's moderation task, and the revision's author (moderators too) through the revision's `checks` route.
- **Open lists.** Normalize `rule`, link `status` and `error`, and reason codes can gain values without notice; clients label the ones they know. Core keeps any value shaped like a code.
- **`restricted` is not broken.** It means the site refuses robots (401/403/429). Core's broken count, and each link's broken flag, cover `broken` and `unreachable` only, as the worker's own count does.
- **Stored copies are capped** at the worker's own limits: 500 text findings and 100 links. Counts say how many were left out.

## 5. Search

Two public routes over the worker's functions, rate-limited per client address (`app.search.*`) because anyone can call them. Behind the Cloudflare Tunnel the address is `CF-Connecting-IP`, which Cloudflare overwrites; that header is only trustworthy because `docker-compose.deploy.yml` binds the app's port to loopback, so nothing but the tunnel reaches it. An IPv6 address counts by its /64. Without the header (local dev) the socket's address is used.

The index holds exactly what the article list publishes and lags it by up to 30 s. The `snippet` the worker returns is already-escaped HTML with `<mark>`; every other field is plain text.

## 6. Configuration

Core and the worker name the same store and buckets differently; each pair must be equal.

| What | Worker reads | Core reads | Local default |
|---|---|---|---|
| Database | `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | the same | see `.env.example` |
| Store endpoint | `MINIO_ENDPOINT` (`host:port`) | `STORAGE_ENDPOINT` (URL) | `localhost:9000` |
| Store credentials | `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | see `.env.example` |
| Uploads bucket | `MINIO_RAW_BUCKET` | `STORAGE_STAGING_BUCKET` | `submissions-raw` |
| Variants and originals bucket | `MINIO_DERIVED_BUCKET` | `STORAGE_MEDIA_BUCKET` | `submissions-derived` |

Both compose files already wire the worker's names from core's.

## 7. Trying it locally

`docker compose up` from the repo root starts Postgres, MinIO and the worker; run core with `SPRING_PROFILES_ACTIVE=dev`, then upload through the real API (reserve → PUT → complete) and poll the item until it is READY. Submitting an article revision shows its checks within seconds (links can take a minute). Search answers once the worker has synced, about 30 s after start.

Without the worker, the dev fixtures drive the same paths: `POST /api/dev/test-media` with `withQueueJob` mints a PROCESSING item whose job no worker will claim, `POST /api/dev/test-media/{mediaId}/job-result` writes the worker's answer onto it for core's real poll to apply, and `POST /api/dev/test-revision-checks` mints a check in any state.
