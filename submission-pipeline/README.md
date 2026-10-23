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
| `internal/handler` | The job handlers: image and panorama, spreadsheet, text check. |
| `internal/imagepipe` | The image engine, built on libvips. |
| `internal/storage` | MinIO/S3 client. |
| `internal/search` | Article search index. `schema.sql` holds all of it. |
| `internal/install` | Applies both `schema.sql` files once core's migrations have run. |
| `internal/normalize` | Finds Persian/Arabic text problems (reports only, never rewrites). |
| `internal/tabular` | Inspects CSV/XLSX files for structural faults. |
| `internal/config` | Settings from environment variables. |
| `cmd/worker` | The service. |
| `cmd/imgpipe`, `cmd/normalize`, `cmd/tabular`, `cmd/search` | Small command-line tools over the packages above. |

The worker runs four job types: `image`, `panorama`, `tabular` (spreadsheets) and
`normalize` (text checks on an article revision). `geo` is not built yet.

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
VALUES ('image', '{"submission_id": "9b1e7c3a", "object_key": "uploads/9b1e7c3a",
                   "declared_sha256": "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg="}')
RETURNING id;
```

   * `object_key` is where the original sits in the raw bucket.
   * `submission_id` becomes a folder name in the derived bucket, so it may only
     contain letters, digits, `_` and `-` (up to 64 characters). Anything else is
     rejected as `malformed_payload`.
   * `declared_sha256` (optional) is the hash the uploader declared, as standard
     padded base64. When it is given, a file whose bytes hash to anything else is
     refused.
   * Keep the returned `id`; it is the cheapest way to find the result later.
3. Read the outcome from the same row:

```sql
SELECT status, result FROM jobs WHERE id = $1;
```

The insert wakes the worker immediately (a trigger sends `NOTIFY jobs`). If a
notification is ever missed, the worker's polling picks the job up anyway.

### What the worker does

* Downloads the original (capped by `WORKER_MAX_FILE_BYTES`).
* Hashes it with SHA-256 before decoding it, and refuses it if the hash differs from
  `declared_sha256`. The hash is reported as `sha256` either way: it proves the file is
  the one that was uploaded, and it is what duplicate uploads are recognised by.
* Reads the image header and refuses anything over 100 megapixels or with a side
  over 30,000 px, before decoding a single pixel. This is what stops a tiny file
  that claims to be 40,000 x 40,000.
* Decodes with libvips. JPEG, PNG, WebP, GIF, TIFF, HEIC and AVIF were all tested.
* Applies the EXIF rotation, converts colours to sRGB, and strips all metadata,
  GPS included.
* Writes WebP (quality 85) at **320, 640, 1280 and 1920 px wide**. Height follows
  the image's shape. A width above the source's is skipped rather than upscaled
  (the source's own width is kept: that is a re-encode), and an image narrower
  than all of them gets one variant at its own width.
* Makes a **placeholder**: the whole image 24 px wide as a low-quality WebP, returned
  inline in the result as a `data:image/webp;base64,…` URI (a few hundred bytes, never
  over 16 KiB). The frontend shows it stretched while the real variant loads, so there
  is a soft preview of the right shape instead of an empty box. It is not stored as a file.
* Uploads to the **derived** bucket:
  * `pending/<submission_id>/w320.webp`, `w640.webp`, `w1280.webp`, `w1920.webp`
    (Content-Type `image/webp`). This is core's layout: it accepts no variant
    outside `pending/<id>/`, moves that folder to `public/` on approval, and
    deletes it when the item is rejected or removed.
  * `originals/<submission_id>/original.<ext>`: a byte-for-byte copy of the upload, with the
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
    "sha256": "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=",
    "placeholder": "data:image/webp;base64,UklGRjAAAABXRUJQVlA4...",
    "variants": [
      {"label": "w320", "format": "webp", "key": "pending/9b1e7c3a/w320.webp", "width": 320, "height": 427, "bytes": 11874}
    ],
    "original": {"key": "originals/9b1e7c3a/original.jpg", "bytes": 2481152, "content_type": "image/jpeg"}
  }
}
```

`width`/`height` are the image as displayed (after rotation). Every variant carries
`format` (`webp`). For a refusal:
`"outcome": "rejected", "reason": {"code": "corrupt_file"}`.

The codes about the file are core's own (`MediaFailureCode`), so the uploader sees
a translated message for each. `reason.detail` is for the logs only; for
`dimensions_too_large`, `detail.limit` says which limit (`side`, `pixels` or `bytes`).

| Reason code | When |
|---|---|
| `unsupported_format` | The file does not start like any image format. |
| `corrupt_file` | It starts like an image but cannot be read. |
| `dimensions_too_large` | A side over 30,000 px, over 100 megapixels, or over `WORKER_MAX_FILE_BYTES`. |
| `checksum_mismatch` | The file's SHA-256 is not `declared_sha256`. |
| `object_missing` | `object_key` is not in the raw bucket. Not about the file, so core shows its generic "rejected". |
| `malformed_payload` | Bad JSON, an invalid `submission_id`, or a `declared_sha256` that is not base64 of 32 bytes. A bug in whoever enqueued the job; core shows its generic "rejected". |

### Panoramas

A 360 degree panorama is the same job with `type` `panorama`, the same payload, and
the same result shape. The differences, from core's contract:

* It must be **2:1** (width ÷ height within 2 ± 0.01), as equirectangular images are;
  anything else is refused with `panorama_aspect_ratio`, before any decode.
* Variants are **`2k` (2048×1024), `4k` (4096×2048) and `8k` (8192×4096)** WebP, at
  `pending/<submission_id>/2k.webp` and so on, `label` being the size name. The same
  rules apply: nothing upscaled, the source's own width kept.
* The pixel budget is **135 MP** instead of 100, so a 16384×8192 panorama fits.

```sql
INSERT INTO jobs (type, payload)
VALUES ('panorama', '{"submission_id": "9b1e7c3a", "object_key": "uploads/9b1e7c3a"}');
```

### Retries and crashes

* Failures retry with exponential backoff and jitter (30 s base). The default is 5
  attempts; a job can set its own `max_attempts`.
* A worker keeps a lock on its job and renews it every minute. If the worker dies,
  the lock lapses (10 minutes) and another worker takes the job over.
* If a worker died on a job's last allowed attempt, the job is marked `failed`
  rather than run again. Otherwise a file that crashes the worker would crash every
  worker in turn.
* On shutdown the worker stops taking jobs and lets running ones finish.

## Spreadsheet jobs

```sql
INSERT INTO jobs (type, payload)
VALUES ('tabular', '{"submission_id": "9b1e7c3a", "object_key": "uploads/9b1e7c3a"}');
```

The worker downloads the file from the raw bucket and decides from its bytes whether it is
CSV or XLSX (never from a name). It reports the file's shape: `format`, `rows` (data rows,
header excluded), `columns`, and `problems`, grouped by kind with a count and up to ten
sample row numbers each. The problem codes are `no_rows`, `no_header`, `duplicate_column`,
`blank_column_name`, `empty_row`, `row_longer_than_header` and `unreadable_rows`.

**A file with problems is not rejected.** The outcome is `ok` with the report, for a reviewer
to decide on. The values themselves are never judged. Only these are refused:

| Reason code | When |
|---|---|
| `unsupported_format` | Not CSV or XLSX (an empty file included). |
| `corrupt_file` | Starts like one of them but cannot be read through. |
| `file_too_large` | Over `WORKER_MAX_FILE_BYTES`. |
| `object_missing`, `malformed_payload` | As for images. |

```json
"tabular": {"format": "csv", "rows": 3, "columns": 3,
            "problems": [{"code": "empty_row", "count": 1, "sample_rows": [2]}]}
```

## Text check jobs (normalize)

```sql
INSERT INTO jobs (type, payload)
VALUES ('normalize', '{"revision_id": "<article_revisions.id>"}');
```

The worker reads that revision from core's tables, and checks its title, summary and the
prose of its body (the same text `search_body_text` gives search) against the rules for the
translation's language: `fa`, `ar` or `en`, region ignored (`fa-IR` is `fa`).

It **never changes the revision**. Each finding is a suggestion: an Arabic ي or ك in a Persian
article, an invisible character, mixed digits. A reviewer accepts or rejects it, because a
spelling can be a deliberate quotation. The same letters in an Arabic article are not findings.

```json
"normalize": {"revision_id": "…", "locale": "fa", "count": 2,
              "findings": [{"rule": "…", "line": 2, "column": 4, "offset": 21,
                            "found": "ي", "suggest": "ی", "context": "جزيره كيش"}]}
```

`count` is every finding; `findings` keeps the first 500, and `findings_truncated` says how
many were left out. `line` and `column` count in that combined text: the title is line 1.

| Reason code | When |
|---|---|
| `revision_missing` | No revision has that id. |
| `unsupported_locale` | There are no rules for the translation's language. |
| `malformed_payload` | Bad JSON, or `revision_id` is not a UUID. |

## Search

`internal/search/schema.sql` creates a `search_documents` table and the functions
around it. It indexes every **published** translation, by the same rule as core's
article list: the translation has a `current_revision_id`, and its article's
subject, if it has one, is not deleted.

It reads these tables and nothing else: `subjects(id, kind, deleted_at)`,
`articles(id, subject_id)`, `article_translations(id, article_id, language, slug,
current_revision_id)`, `article_revisions(id, title, summary, body)`, and a
subject's names: `labels(subject_id, value)`, `subject_aliases(subject_id, value)`
and `subject_redirects(source_id, target_id)`. If those names or columns change,
update `schema.sql`. It has no triggers, no foreign keys
and no views on them, so a search problem can never fail an article write or block
one of core's migrations: the index simply falls behind.

The article body is free-form JSON, so the text is taken from the values of the
keys `title`, `text`, `caption` and `alt` at any depth.

**Other names, like Wikipedia's redirects.** An article about a subject is also
found by the subject's name in every language (its labels), its aliases, and the
names and aliases of any subject merged into it. They count as much as the title,
so "Qays" finds the Kish article as if it were its title, and a hit found that way
says so in `matched_name`, for showing "Qays → Kish Island". Typo matching covers
them too.

**Keeping it fresh:** the worker syncs every `SEARCH_SYNC_INTERVAL` (30 s), so a
newly approved article is searchable within that. Unpublished or deleted articles
leave the index at the same sync, and so does a change to a subject's names: a new
alias or a merge needs no new revision.

**Querying:**

```sql
SELECT * FROM search_articles('تنگه هرمز', 'fa', NULL, 20, 0);
--                            query,       language, subject_kind, limit, offset
```

Returns `translation_id, article_id, language, subject_kind, slug, title,
matched_name, snippet, score, total` (`total` is the full match count on every row,
for paging; `matched_name` is null when the title itself matched).
`subject_kind` is null for an article with no subject, and a kind filter leaves
those out. `snippet`
is HTML with matches in `<mark>`, already escaped, so it is safe to render as is.
Every other column is plain text and must be escaped by whoever displays it.

What it matches: Persian/Arabic spelling variants (ی/ي, ک/ك, hamza forms), ZWNJ,
diacritics and digits (۱۴۰۲ = 1402); words as you type them (prefixes); English word
forms for `en` articles; and misspelt titles when nothing matches exactly. Titles
rank above slug and summary, which rank above the body. Persian and Arabic have no
stemmer in Postgres, so prefix matching stands in for one.

**Suggestions while typing:**

```sql
SELECT * FROM search_suggest('هرم', 'fa', 8);   -- text so far, language, limit (max 20)
```

Returns `translation_id, article_id, language, slug, title, matched_name`: titles and
other names that start with what was typed, every word as a prefix. A title that
starts with the text comes first, then an other name that does, then shorter titles
before longer ones. It reads one small index, so it can run on every keystroke.

**"Did you mean":**

```sql
SELECT search_did_you_mean('hormuzz strait', NULL);   -- → 'Hormuz strait'
```

Each word of the query that no indexed article contains is replaced by the closest
word some article does contain; known words are kept as typed. It returns null when
there is nothing to correct, and a spelling that folds to a known word (Arabic ي for
Persian ی) is not a typo. The vocabulary (`search_terms`) is rebuilt whenever a sync
changes the index. Show it above the results when a search finds nothing or little.

**Changing the index's shape:** `search_documents` carries a version in its table
comment. When `schema.sql` changes the table, bump the version: the worker then
drops the old index on start and the next sync rebuilds it. The same goes for a
change to the folding rules in `search_fold`, since stored rows are not rewritten.

## Database setup

Nothing to do by hand. On start the worker waits until core's Flyway has run
(`flyway_schema_history` exists), then applies, each in its own transaction:

1. `internal/queue/schema.sql`: the `jobs` table, its constraints and indexes, and
   the insert trigger that wakes workers.
2. `internal/search/schema.sql`: the `pg_trgm` extension, the index table and its
   functions. It needs core's article tables, and is retried until they exist.

Both are idempotent and are applied again on every start, which is how a changed
schema reaches an existing database. It waits for Flyway because Flyway refuses to
migrate a non-empty schema that has no history table: a `jobs` table created first
would stop core from starting on a fresh database.

Until then the worker idles: the queue and the search sync each log once that their
table is missing, and resume as soon as it exists.

The worker's database user needs `CREATE` on the schema and the right to create
`pg_trgm` (core's user in the compose files has both); `SELECT, UPDATE` on `jobs`;
`SELECT` on the seven tables above; and `LISTEN`. It never inserts into `jobs`, and
finished rows are never deleted by the worker, so decide how long to keep them.

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
* **Compose.** `docker-compose.yml` builds the `pipeline` service from the `Dockerfile` and
  starts it with everything else on `docker compose up`. The first build takes a few minutes.
  `deploy/frontend/docker-compose.yml` pulls the published image instead, behind the `pipeline`
  profile (`docker compose --profile pipeline up -d`), because a stack that pulled it by default
  would not start at all until the image had been published.
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
QUEUE_TEST_DSN=postgres://user:pass@localhost:5432/scratch go test ./internal/queue/ ./internal/install/
SEARCH_TEST_DSN=postgres://user:pass@localhost:5432/scratch go test ./internal/search/
```

**Use a scratch database.** The tests drop and recreate their tables: `jobs` and
`queue_probe` for the queue; `search_documents`, `subjects`, `articles`,
`article_translations` and `article_revisions` for search. The install tests
create and drop a database of their own, `install_test`, on the same server.

## Command-line tools

```bash
go run -tags vips ./cmd/imgpipe -in photo.jpg -out ./out   # run one image through the pipeline
go run ./cmd/normalize -in article.txt -locale fa           # report text problems as JSON
go run ./cmd/tabular -in data.xlsx                          # report structural faults as JSON
go run ./cmd/search -q "تنگه هرمز" -lang fa -dsn "$DSN"     # query the index; -sync, -schema also available
```

## For the app side

See [CORE_CHANGES.md](CORE_CHANGES.md): what core changes to send image jobs here
instead of Redis, how to read results, the one search query to call, and which
differences from `docs/MEDIA_PIPELINE.md` are deliberate.

## Not done yet

* **The contract with core**: see [CORE_CHANGES.md](CORE_CHANGES.md).
* **Geo jobs.** Not wired in, because `internal/geo` is not built.
* **Geo.** `internal/geo` (shapefile to GeoJSON) is unimplemented, so there is no
  `shp2geojson` command.
* **Format allowlist.** Anything libvips can read is accepted today. Limiting it to a list of
  formats would be a sensible tightening.
* **Untested:** a real camera HEIC straight from an iPhone, animated GIFs, and heavy load.
