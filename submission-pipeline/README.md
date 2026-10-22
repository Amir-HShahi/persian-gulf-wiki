# submission-pipeline

A Go worker that processes submitted content. This file explains what is in the
module, how the rest of the system talks to it, and what the app side has to do
to use it.

The worker has no HTTP API (apart from `/healthz`) and uses no Redis. **Postgres
is the meeting point:** the app inserts a row into a `jobs` table, the worker
picks it up and writes the result back onto the same row. Files go through two
MinIO/S3 buckets.

```
app ──INSERT──▶ jobs table ◀──claim + lock── worker ──download──▶ raw bucket
app ◀──reads result── jobs.result              worker ──upload───▶ derived bucket

worker ──every 30s──▶ search_documents (built from the article tables)
app ──SELECT search_articles(...)──▶ ranked hits + snippets
```

## What is in here

| Path | What it does |
|---|---|
| `internal/queue` | The job queue on Postgres: claiming, heartbeats, retries, dead-lettering. `schema.sql` creates the table. |
| `internal/handler` | The image job handler. |
| `internal/imagepipe` | The image engine, built on libvips. |
| `internal/storage` | MinIO/S3 client. |
| `internal/search` | Article search index. `schema.sql` holds all of it. |
| `internal/normalize` | Finds Persian/Arabic text problems (reports only, never rewrites). |
| `internal/tabular` | Inspects CSV/XLSX files for structural faults. |
| `internal/config` | Settings from environment variables. |
| `cmd/worker` | The service. |
| `cmd/imgpipe`, `cmd/normalize`, `cmd/tabular`, `cmd/search` | Small command-line tools over the packages above. |

Only **image jobs** are wired into the worker today. `normalize` and `tabular`
work as packages and CLIs but have no job type yet.

## Why it is built this way

* **Postgres is the only channel between the app and the worker.** The app can insert a job
  row in the same transaction as its own change, so the two cannot disagree. With a stream,
  the enqueue happens outside the database transaction, and the app has to publish first and
  tolerate an orphaned job when the commit then fails (core's `MediaJobPublisher` does exactly
  that). A row is also something you can look at: a stuck job is a `SELECT`, and the result is
  on the same row. The cost is that the worker needs a database role, and the app inserts a
  row instead of writing to a stream.
* **One generic `jobs` table, not a table per feature.** Locking, retries and crash recovery are
  written and tested once. A new kind of work is a new handler, not a new queue.
* **The result goes back on the job row**, not into the app's tables, so the worker never has to
  know the app's schema. Search is the one exception: it reads the article tables (read-only),
  because the index is built from them.
* **A job that kills its worker is parked, not retried forever.** If an image exhausts memory,
  no worker survives to record a failure, so the only trace is a lapsed lock with every attempt
  spent. Without a rule for that, each worker would pick the job up and die on it in turn.
* **Shutdown lets a running job finish.** Otherwise every deploy would abort in-flight jobs and
  leave them locked until the lock expired.
* **The original is kept, and copied beside the variants.** Variants are what gets served; the
  original is the only source if a new size or format is ever wanted, and the copy survives any
  cleanup of the raw bucket. It is private because it still carries the camera's metadata,
  including location.
* **Variants are WebP at 320, 640, 1280 and 1920 px wide, sized by width.** That is how
  responsive images choose a file. Nothing is upscaled, and since the original is not served,
  1920 is the sharpest image a visitor sees. An image narrower than every width still gets one
  variant, so no accepted upload ends up with nothing to show.
* **Metadata is stripped and colours are converted to sRGB.** The first is privacy (GPS). The
  second matters for iPhone photos, which are Display P3: left as is, their colours come out
  wrong once the profile is removed. EXIF rotation is applied before sizing, because phones
  store portraits sideways and sizing from the stored shape makes every variant too small.
* **The header is checked before any pixel is decoded**, so a few bytes that claim to be
  40,000 x 40,000 pixels are refused instead of allocated.
* **libvips is behind a build tag, and the worker refuses to start without it.** The queue
  dead-letters any job type it has no handler for, so a worker that cannot process images would
  quietly discard real jobs.
* **Search lives in SQL.** The folding rules exist once, so an article is indexed and a query is
  folded the same way. It needs no extra service, and Postgres has no Persian dictionary to
  lean on anyway.
* **Search polls instead of reacting to events.** Nothing is added to the app's write path, so a
  search problem can never fail an article save, and a missed signal cannot leave the index
  wrong for good.

## Image jobs

### What the app does

1. Put the upload in the **raw** bucket under some key.
2. Insert a job:

```sql
INSERT INTO jobs (type, payload)
VALUES ('image', '{"submission_id": "9b1e7c3a", "object_key": "uploads/9b1e7c3a"}')
RETURNING id;
```

   * `object_key` is where the original sits in the raw bucket.
   * `submission_id` becomes a folder name in the derived bucket, so it may only
     contain letters, digits, `_` and `-` (up to 64 characters). Anything else is
     rejected as `malformed_payload`.
   * Keep the returned `id`; it is the cheapest way to find the result later.
3. Read the outcome from the same row:

```sql
SELECT status, result FROM jobs WHERE id = $1;
```

The insert wakes the worker immediately (a trigger sends `NOTIFY jobs`). If a
notification is ever missed, the worker's polling picks the job up anyway.

### What the worker does

* Downloads the original (capped by `WORKER_MAX_FILE_BYTES`).
* Reads the image header and refuses anything over 100 megapixels or with a side
  over 30,000 px, before decoding a single pixel. This is what stops a tiny file
  that claims to be 40,000 x 40,000.
* Decodes with libvips. JPEG, PNG, WebP, GIF, TIFF, HEIC and AVIF were all tested.
* Applies the EXIF rotation, converts colours to sRGB, and strips all metadata,
  GPS included.
* Writes WebP (quality 85) at **320, 640, 1280 and 1920 px wide**. Height follows
  the image's shape. A width at or above the source's is skipped rather than
  upscaled, and an image narrower than all of them gets one variant at its own
  width.
* Uploads to the **derived** bucket:
  * `<submission_id>/w320.webp`, `w640.webp`, `w1280.webp`, `w1920.webp` (Content-Type `image/webp`)
  * `<submission_id>/original.<ext>`: a byte-for-byte copy of the upload, with the
    extension taken from the file's content. **Do not serve this one publicly:** it
    still carries whatever the camera wrote, including location.
* Never modifies or deletes anything in the raw bucket. The storage client has no
  delete operation.

### The result

`jobs.status` moves `pending` → `running` → `done` or `failed`.

* `done` means the worker ran to the end. **A refused file is also `done`**, with
  `outcome: "rejected"` and a reason: the pipeline worked and the answer was no.
  It is never retried.
* `failed` means the worker gave up after its retries (or could never run the job).
  `last_error` says why. Nothing retries it; it needs a person.

`result` for a success:

```json
{
  "version": 1, "job_id": 42, "type": "image", "outcome": "ok",
  "attempt": 1, "duration_ms": 812, "finished_at": "2026-10-12T09:30:00Z",
  "image": {
    "width": 3000, "height": 4000, "format": "jpeg",
    "variants": [
      {"label": "w320", "key": "9b1e7c3a/w320.webp", "width": 320, "height": 427, "bytes": 11874}
    ],
    "original": {"key": "9b1e7c3a/original.jpg", "bytes": 2481152, "content_type": "image/jpeg"}
  }
}
```

`width`/`height` are the image as displayed (after rotation). For a refusal:
`"outcome": "rejected", "reason": {"code": "image_unreadable"}`.

| Reason code | When |
|---|---|
| `image_unreadable` | Not an image, or corrupt. |
| `image_too_many_pixels` | Over 100 megapixels. |
| `image_dimension_too_large` | A side over 30,000 px. |
| `file_too_large` | Over `WORKER_MAX_FILE_BYTES`. |
| `object_missing` | `object_key` is not in the raw bucket. |
| `malformed_payload` | Bad JSON or an invalid `submission_id`. |

### Retries and crashes

* Failures retry with exponential backoff and jitter (30 s base). The default is 5
  attempts; a job can set its own `max_attempts`.
* A worker keeps a lock on its job and renews it every minute. If the worker dies,
  the lock lapses (10 minutes) and another worker takes the job over.
* If a worker died on a job's last allowed attempt, the job is marked `failed`
  rather than run again. Otherwise a file that crashes the worker would crash every
  worker in turn.
* On shutdown the worker stops taking jobs and lets running ones finish.

## Search

`internal/search/schema.sql` creates a `search_documents` table and the functions
around it. It indexes every **published** translation, meaning one whose
`current_revision_id` is set.

It reads these article tables and nothing else: `articles(id, entity_type)`,
`article_translations(id, article_id, language, slug, current_revision_id)` and
`article_revisions(id, title, summary, body)`. If those names or columns change,
update `schema.sql`. It has no triggers and no foreign keys into them, so a search
problem can never fail an article write: the index simply falls behind.

The article body is free-form JSON, so the text is taken from the values of the
keys `title`, `text`, `caption` and `alt` at any depth.

**Keeping it fresh:** the worker syncs every `SEARCH_SYNC_INTERVAL` (30 s), so a
newly approved article is searchable within that. Unpublished or deleted articles
leave the index at the same sync.

**Querying:**

```sql
SELECT * FROM search_articles('تنگه هرمز', 'fa', NULL, 20, 0);
--                            query,       language, entity_type, limit, offset
```

Returns `translation_id, article_id, language, entity_type, slug, title, snippet,
score, total` (`total` is the full match count on every row, for paging). `snippet`
is HTML with matches in `<mark>`, already escaped, so it is safe to render as is.
Every other column is plain text and must be escaped by whoever displays it.

What it matches: Persian/Arabic spelling variants (ی/ي, ک/ك, hamza forms), ZWNJ,
diacritics and digits (۱۴۰۲ = 1402); words as you type them (prefixes); English word
forms for `en` articles; and misspelt titles when nothing matches exactly. Titles
rank above slug and summary, which rank above the body. Persian and Arabic have no
stemmer in Postgres, so prefix matching stands in for one.

If the folding rules in `search_fold` ever change, run `TRUNCATE search_documents`
and the worker rebuilds the index on its next sync.

## Database setup

Add these two files as migrations, in this order (both can be re-run safely):

1. `internal/queue/schema.sql`: the `jobs` table, its constraints and indexes, and
   the insert trigger that wakes workers.
2. `internal/search/schema.sql`: needs the `pg_trgm` extension and the article
   tables to exist.

A worker that starts before these migrations have run does not fail: it logs one warning per
component (the queue, the search sync) and idles, then picks up work as soon as the tables exist.

The worker's database user needs `SELECT, UPDATE` on `jobs`; `SELECT` on the three
article tables; full access to `search_documents`; and `EXECUTE` on the `search_*`
functions. It also uses `LISTEN`. It never inserts into `jobs`, and finished rows
are never deleted by the worker, so decide how long to keep them.

## Configuration

All environment variables. `-` means required.

| Variable | Default | |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `core` | |
| `DB_USERNAME` / `DB_PASSWORD` | - | |
| `DB_MAX_CONNS` | `8` | |
| `MINIO_ENDPOINT` | - | `host:port` |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | - | |
| `MINIO_USE_SSL` | `false` | |
| `MINIO_RAW_BUCKET` / `MINIO_DERIVED_BUCKET` | - | originals / results |
| `WORKER_CONCURRENCY` | `4` | jobs at once |
| `WORKER_MAX_FILE_BYTES` | 256 MiB | |
| `WORKER_JOB_TIMEOUT` | `5m` | |
| `WORKER_POLL_INTERVAL` | `10s` | safety net behind `NOTIFY` |
| `WORKER_HEARTBEAT_INTERVAL` / `WORKER_LOCK_DURATION` | `1m` / `10m` | heartbeat must be at most half the lock |
| `WORKER_MAX_ATTEMPTS` | `5` | for jobs that set none |
| `WORKER_RETRY_BASE_DELAY` | `30s` | |
| `SEARCH_SYNC_INTERVAL` / `SEARCH_SYNC_BATCH` | `30s` / `500` | |
| `LOG_LEVEL` | `info` | |

The worker starts only if the settings are consistent, and reports every problem
at once. The `REDIS_*` settings in `.env.example` are not used by this worker.

## Running it

libvips is a C library, so the image engine is behind the `vips` build tag:

```bash
go build -tags vips ./cmd/worker
```

A build without the tag compiles, but the worker **refuses to start**: the queue
dead-letters any job type it has no handler for, so a worker that cannot process
images would discard real jobs.

The `Dockerfile` (build context is the repository root) builds with libvips and the
HEIF decoders, fails the build if the HEIF loader is missing, runs as a non-root
user, and health-checks `GET :8080/healthz`. That port is fixed in the code, so
inside a container that publishes nothing it will not clash with the app.

## CI and deployment

* **`.github/workflows/submission-pipeline.yml`** runs `go vet`, builds (with and without the
  `vips` tag), runs every test with `-race`, and scans the dependencies with Trivy (report-only,
  like core's scan). The queue and search tests run, not skip: the job starts
  `postgis/postgis:16-3.4`, the image production uses. It only runs for changes under
  `submission-pipeline/`.
  It is a workflow of its own, not a job in `ci.yml`, on purpose: Deploy Staging waits for the
  whole `CI` workflow to succeed, so a Go failure inside it would stop core from deploying.
* **Compose.** `docker-compose.yml` and `deploy/frontend/docker-compose.yml` have a `pipeline`
  service behind the `pipeline` profile, so it is opt-in:
  `docker compose --profile pipeline up -d pipeline`. The root file builds it from the
  `Dockerfile`; the frontend file pulls the published image. It is opt-in because the first would
  force everyone to build a libvips image, and the second would stop the whole stack from
  starting whenever the image had not been published yet.
* **`docker-compose.deploy.yml`** has a `pipeline` service too. It is not in `app`'s
  `depends_on` and nothing depends on it. It has a default image so the file stays valid when
  `PIPELINE_IMAGE` is unset: Compose rejects the whole file for a service with a blank image,
  which would stop the app deploying.
* **`deploy/deploy.sh`** takes an optional second argument, the worker image. Once the app is
  healthy it pulls and starts the worker. If that fails it prints a warning and the deploy still
  succeeds: a worker problem must not make a good app deploy look broken or send it into the
  rollback path. Without the argument the script behaves as it did before.
* **The deploy workflows** build and push `persian-gulf-wiki-pipeline` next to the core image
  (`staging` and the sha tag; or the version and `latest`). That step has `continue-on-error`, so
  a failed worker build cannot stop core deploying, and a warning says it was skipped. The worker
  image is only passed on to `deploy.sh` if the build succeeded.
* The first publish creates the `persian-gulf-wiki-pipeline` package on GHCR. Give it the same
  visibility and access as the core image, or the server cannot pull it.

## Tests

```bash
go test -tags vips ./...
```

The queue and search tests run against a real Postgres and **skip unless a database
is given** (CI gives them one, so they run there):

```bash
QUEUE_TEST_DSN=postgres://user:pass@localhost:5432/scratch go test ./internal/queue/
SEARCH_TEST_DSN=postgres://user:pass@localhost:5432/scratch go test ./internal/search/
```

**Use a scratch database.** The tests drop and recreate their tables: `jobs` and
`queue_probe` for the queue; `search_documents`, `articles`, `article_translations`
and `article_revisions` for search.

## Command-line tools

```bash
go run -tags vips ./cmd/imgpipe -in photo.jpg -out ./out   # run one image through the pipeline
go run ./cmd/normalize -in article.txt -locale fa           # report text problems as JSON
go run ./cmd/tabular -in data.xlsx                          # report structural faults as JSON
go run ./cmd/search -q "تنگه هرمز" -lang fa -dsn "$DSN"     # query the index; -sync, -schema also available
```

## For the app side

1. Add the two `schema.sql` files as migrations and grant the worker's user the
   privileges above.
2. Insert `image` jobs as shown, and read `status` and `result` from the row.
3. Call `search_articles(...)` for queries.
4. Make sure both buckets exist, keep `original.*` private, and decide how long to
   keep finished jobs and files.

## Where this differs from `docs/MEDIA_PIPELINE.md`

That document describes a Redis-stream contract between core and a worker. This worker takes its
jobs from Postgres instead, so the two do not line up yet. For whoever brings them together:

| | `docs/MEDIA_PIPELINE.md` | This worker |
|---|---|---|
| Transport | Redis streams `submissions` and `submission-results` | The Postgres `jobs` table |
| Job id | UUID `job_id`, echoed in the result | `jobs.id` (a number) in the result's `job_id` |
| Job fields | `version`, `job_id`, `type` (`media.*`), `submission_id`, `object_key`, `content_type`, `declared_bytes`, `declared_sha256` | `type` `image`, with `submission_id` and `object_key` |
| Result | `status`, and a `media` block with `sha256`, `placeholder`, `variants[size, format, ...]` | `outcome`, and an `image` block with `variants[label, ...]` and `original` |
| Variant keys | `pending/{mediaId}/{size}.{ext}` | `{submission_id}/w{width}.webp` |
| Widths | 320, 640, 1280, 1920, 2560 | 320, 640, 1280, 1920 |
| Accepted formats | JPEG, PNG, WebP | anything libvips can read |
| The original | deleted by the worker after the result is published | never deleted, and copied beside the variants |
| Checks | SHA-256 against the declared one, panorama 2:1, inline placeholder | none of these |
| Reject codes | five fixed codes | the six above |

Until core inserts into `jobs` (or the worker is pointed at whichever table core uses), nothing
feeds this worker and it idles. Closing the gap is a decision for both sides, on the table
and on each row of the table above; the worker side of it is a modest change.

## Not done yet

* **The contract with core**, as above.
* **Other job types.** Spreadsheet, normalize and geo jobs are not wired in.
* **Geo.** `internal/geo` (shapefile to GeoJSON) is unimplemented, so there is no
  `shp2geojson` command.
* **Format allowlist.** Anything libvips can read is accepted today. Limiting it to a list of
  formats would be a sensible tightening.
* **Untested:** a real camera HEIC straight from an iPhone, animated GIFs, and heavy load.
