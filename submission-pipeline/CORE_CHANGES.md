# What core needs to change to use this worker

For Amir. The worker is in `docker-compose.yml` and the deploy stack, and it starts with them.
Search works with core's schema as it is today (V33), with no core change apart from calling it.
Images do not reach the worker yet: core sends them through Redis, and the worker takes its
jobs from Postgres. This file lists what core has to change, why, and what stays as it is.

## 1. Send image jobs through the `jobs` table, not the Redis stream

**Why Postgres rather than Redis.** `MediaJobPublisher` explains the problem itself: the `XADD`
cannot be part of the database transaction, so it runs before the commit, and a commit that then
fails leaves an orphaned job. An `INSERT INTO jobs` runs inside the same `@Transactional` as the
`PROCESSING` status change. Both commit or neither does, and the job id is known before the
commit. The result is written back on the same row, so there is no result stream, consumer
group, reclaim loop or delivery counter to maintain. A stuck job is visible with a `SELECT`.

Redis stays for `MediaUploadLimitService`. Only the two streams go.

**The worker creates the table.** On start it waits until `flyway_schema_history` exists, then
applies `internal/queue/schema.sql` and `internal/search/schema.sql`. Both are idempotent and
re-applied on every start. Waiting matters: Flyway refuses to migrate a non-empty schema with no
history table, so a worker that created `jobs` first would stop core from starting on a fresh
database.

Because of that order, **a core Flyway migration cannot reference `jobs` on a fresh database**:
the table does not exist yet when Flyway runs. If you would rather core owned the table, copy
`internal/queue/schema.sql` into a migration unchanged. It is idempotent, so the worker's own
apply then changes nothing.

### Enqueue (replaces `MediaJobPublisher`)

```sql
INSERT INTO jobs (type, payload)
VALUES (:type, :payload::jsonb)   -- 'image' for IMAGE, 'panorama' for PANORAMA_360
RETURNING id;
```

```json
{"submission_id": "<media id>", "object_key": "<staging key>", "declared_sha256": "<as declared>"}
```

* Run it inside `MediaUploadService.complete`'s transaction.
* `submission_id` may only contain letters, digits, `_` and `-`, up to 64 characters. A UUID fits.
* `declared_sha256` is the value core already has, in the same encoding (standard padded
  base64, not hex). The worker hashes the bytes it downloaded, before decoding them, and
  refuses the file with `checksum_mismatch` (your code, same name) if they differ. A value
  that is not base64 of 32 bytes is `malformed_payload`. Leave the field out only to skip the
  check.
* The worker's own hash comes back as `result.image.sha256`, in the same encoding: store that
  one and run the duplicate check on it, as you do today with `media.sha256`.
* Other extra payload fields are ignored, so keeping `content_type` and `declared_bytes`
  costs nothing.
* The insert fires `NOTIFY jobs`, so the worker starts within milliseconds.

`jobs.id` is a `bigint`, and `article_media.processing_job_id` (V19) is a `uuid`. Either store
the returned id in a `bigint` column, or keep the UUID as `"job_id"` in the payload and match on
`payload->>'job_id'`. The bigint is simpler: the match is by primary key and needs no extra index.

### Read results (replaces `MediaResultConsumer`)

A `@Scheduled` poll every few seconds:

```sql
SELECT m.id AS media_id, j.status, j.result, j.last_error
  FROM article_media m
  JOIN jobs j ON j.id = m.processing_queue_job_id   -- whichever column holds jobs.id
 WHERE m.processing_status = 'PROCESSING'
   AND j.status IN ('done', 'failed');
```

Once core moves an item out of `PROCESSING` the row stops matching, so nothing has to be
acknowledged. A redelivery cannot happen. Until the worker has started once, `jobs` may not
exist: treat "relation does not exist" as "nothing yet".

| Row | Means | Core does what it does today for |
|---|---|---|
| `status = 'done'`, `result.outcome = 'ok'` | processed | `ok` |
| `status = 'done'`, `result.outcome = 'rejected'` | the file was refused, never retried | `rejected`, with `result.reason.code` |

Rejection codes are yours, so `MediaFailureCode.fromPipelineCode` maps them as it is:
`unsupported_format`, `corrupt_file`, `dimensions_too_large` (every size limit: side,
pixel count and file size) and `checksum_mismatch`. Two more can appear that are not about
the file, so they land on your generic `rejected`: `object_missing` (the staging object is
gone) and `malformed_payload` (the job row itself is wrong). `panorama_aspect_ratio` comes
from `panorama` jobs whose image is not 2:1 (within 2 ± 0.01).
| `status = 'failed'` | the worker gave up after its retries; `last_error` says why | `failed` → `processing_error` |

Retry window: 5 attempts with 30 s backoff doubling, plus up to a 5 min job timeout each. That
is well inside the 60-minute stuck-processing sweep, which can stay as it is.

### Result fields, mapped to `PipelineResult`

| `PipelineResult` | Worker's `result` |
|---|---|
| `status` | `outcome` (the row's own `status` is the job's lifecycle, a different thing) |
| `job_id` (UUID) | not needed: core already knows which row it read |
| `submission_id` | in the row's `payload` |
| `type` `media.image` / `media.panorama` | `type` `image` / `panorama` |
| `media.sha256` | `image.sha256` (recomputed by the worker, standard padded base64) |
| `media.placeholder` | `image.placeholder`: `data:image/webp;base64,...`, 24 px wide, under 16 KiB, so your `PLACEHOLDER` check passes as is. Left out (not empty) if one could not be made small enough. |
| `media.width` / `media.height` | `image.width` / `image.height` (after EXIF rotation) |
| `media.variants[].size` | `image.variants[].label` (`w320`, `w640`, ...) |
| `media.variants[].format` | same name, always `webp` |
| `media.variants[].key/bytes/width/height` | same names |

Example `result`:

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

### Object keys: already core's layout

Variants are written to `pending/{mediaId}/w320.webp`, `w640.webp`, `w1280.webp` and
`w1920.webp` in the media bucket, so `MediaProcessingService`'s key check passes and your
approve (move to `public/`) and clean-up (delete the prefix) work unchanged. The file names
carry a `w` before the width; the check only looks at the prefix.

Panoramas follow your §5 exactly: `pending/{mediaId}/2k.webp`, `4k.webp` and `8k.webp`
(2048×1024, 4096×2048, 8192×4096), with `label` `2k`/`4k`/`8k` and `format` `webp`. KTX2
is not made yet; WebP is the format your contract accepts until it is. The pixel budget is
135 MP, as you suggested, so 16384×8192 fits.

The original goes to `originals/{mediaId}/original.<ext>`, outside `pending/`, so publishing
or rejecting an item never deletes it. **One thing for core to decide:** your 7-day sweep of
FAILED items does not reach `originals/`, so originals of rejected or deleted items stay. If
they should go too, add `originals/{mediaId}/` to that sweep and to item deletion.

## 2. Kept on the worker side, on purpose

| | Core's contract | Worker | Why the worker's way |
|---|---|---|---|
| Accepted formats | JPEG, PNG, WebP | also HEIC, AVIF, TIFF, GIF | iPhones upload HEIC by default; refusing it refuses most phone photos. Core's upload endpoint has to allow those content types for this to matter. |
| The original | deleted by the worker | kept, and copied to `originals/{mediaId}/original.<ext>` in the media bucket, outside `pending/` so your clean-up leaves it alone | It is the only source if a new size or format is ever needed. It must never be served: it still has the camera's metadata, including GPS. |
| Pixel limit | about 60 MP suggested | 100 MP, and 30,000 px per side | The header is checked before any decode, so a larger limit costs no safety. Current phones shoot 48 to 200 MP. |
| Widths | up to 2560 | 320, 640, 1280, 1920 | The original is kept, so a 2560 variant can be generated later if a layout needs one. |

## 3. Search: three queries to expose

The worker builds and refreshes the index on its own, every 30 s. Core only queries it.

**Search results:**

```sql
SELECT * FROM search_articles(:q, :language, :subjectKind, :limit, :offset);
```

* Returns `translation_id, article_id, language, subject_kind, slug, title, matched_name,
  snippet, score, total`. `total` is the full match count on every row.
* An article is also found by its subject's other names, in every language: labels, aliases,
  and the names of any subject merged into it, as Wikipedia's redirects work. `matched_name` is
  the name that matched, for showing "Qays → Kish Island", and null when the title did.
* `language` and `subjectKind` may be null. A kind filter leaves out articles with no subject,
  as `findPublishedByLanguage` does.
* `snippet` is HTML that is already escaped, with matches in `<mark>`, and can be rendered as is.
  The other columns are plain text.
* It indexes exactly what `ArticleTranslationRepository.findPublishedByLanguage` publishes: a
  translation with a `current_revision_id`, whose subject, if any, is not deleted. A subject
  that is deleted or restored drops out of results, or comes back, within one sync.

**Suggestions while typing** (cheap enough for every keystroke):

```sql
SELECT * FROM search_suggest(:prefix, :language, :limit);   -- limit 8 by default, at most 20
```

Returns `translation_id, article_id, language, slug, title, matched_name`, matched on titles
and other names by prefix, titles that start with the text first.

**"Did you mean"**, for a search that found nothing or little:

```sql
SELECT search_did_you_mean(:q, :language);   -- 'hormuzz strait' → 'Hormuz strait', or null
```

The index reads only these columns: `subjects(id, kind, deleted_at)`, `articles(id, subject_id)`,
`article_translations(id, article_id, language, slug, current_revision_id)`,
`article_revisions(id, title, summary, body)`, `labels(subject_id, value)`,
`subject_aliases(subject_id, value)` and `subject_redirects(source_id, target_id)`. It uses functions rather than views, so none of
your migrations is blocked by a dependency on these columns. If a migration renames or drops
one of them, search stops updating and logs why. The site is not affected. Please tell me when
that happens.

## 4. Spreadsheets and text checks: ready when you want them

Two more job types run already. Neither needs anything from core until you use it:

* **`tabular`**: `{"submission_id", "object_key"}` for a CSV or XLSX in the staging bucket.
  The result's `tabular` block is the file's shape and its structural problems, for a
  reviewer. A file with problems is `ok`, not rejected.
* **`normalize`**: `{"revision_id"}` for an article revision, any status. The worker reads it
  from your tables and returns `normalize.findings`: suggested text fixes such as an Arabic
  ي in a Persian article. It never writes to the revision. A natural place to enqueue it is
  when a revision is submitted for review, so the moderator sees the findings with it.

Both are described in the README with their result and reject codes.

## 5. Database role

The compose files run the worker as core's database user, which is enough. If it ever gets its
own role, that role needs `CREATE` on `public` (it applies its schema),
`CREATE EXTENSION pg_trgm` (or have it installed once), `SELECT, UPDATE` on `jobs`, `SELECT` on
the seven tables above, and `LISTEN`.
