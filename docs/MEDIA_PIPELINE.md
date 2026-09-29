# Media pipeline contract (core ↔ submission-pipeline)

This document is the contract between **core** (the Spring API) and the **Go worker** in `submission-pipeline/` for article gallery media: images, 360° panoramas and (later) videos. Core's side is implemented. The worker's side is described here for the Go developer to implement.

Core owns this document. Any change to a field, a stream, a code or a key layout is agreed first and lands here in the same change as core's code. If this document and core's code disagree, core's code is right and this document has a bug. Please report it.

## 1. Where the worker fits

```
browser ──1. POST /articles/{id}/media──▶ core        reserve: item UPLOADING, presigned PUT returned
browser ──2. PUT file──────────────────▶ staging     signed over size + sha256; storage rejects any other file
browser ──3. POST …/complete───────────▶ core        HEAD staging, compare size + sha256 → item PROCESSING
core    ──4. XADD job──────────────────▶ redis  submissions
worker  ◀─5. XREADGROUP (group "pipeline")
worker  ──6. read original, validate, strip metadata, write variants ▶ media bucket  pending/{mediaId}/…
worker  ──7. XADD result───────────────▶ redis  submission-results
worker  ──8. delete the original from staging
core    ◀─9. XREADGROUP (group "core"): item READY → moderation task opened
                                    or: item FAILED with a failure code shown to the uploader
```

Once an item is READY, the worker is done with it. Moderation, publication, copying variants to public URLs and deletion are all core's job.

## 2. Configuration mapping

Core and the worker read different variable names for the same store. They must name the same host and buckets.

| What | Worker reads | Core reads | Local default |
|---|---|---|---|
| Redis address | `REDIS_ADDR` | `SPRING_DATA_REDIS_HOST` / `_PORT` | `localhost:6379` |
| Redis password | `REDIS_PASSWORD` | `SPRING_DATA_REDIS_PASSWORD` | empty |
| Job stream | `REDIS_JOB_STREAM` | `REDIS_JOB_STREAM` | `submissions` |
| Worker's group on the job stream | `REDIS_CONSUMER_GROUP` | – | `pipeline` |
| Result stream | `REDIS_RESULT_STREAM` (**new, to add**) | `REDIS_RESULT_STREAM` | `submission-results` |
| Store endpoint | `MINIO_ENDPOINT` (`host:port`) | `STORAGE_ENDPOINT` (URL) | `localhost:9000` |
| Store credentials | `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | see `.env.example` |
| Originals bucket | `MINIO_RAW_BUCKET` | `STORAGE_STAGING_BUCKET` | `submissions-raw` |
| Variants bucket | `MINIO_DERIVED_BUCKET` | `STORAGE_MEDIA_BUCKET` | `submissions-derived` |

`MINIO_RAW_BUCKET` must equal `STORAGE_STAGING_BUCKET`, and `MINIO_DERIVED_BUCKET` must equal `STORAGE_MEDIA_BUCKET`. The Go names stay as they are. Nothing on the worker side needs renaming.

**Local stack:** `docker compose up -d redis minio minio-init` from the repo root. `minio-init` creates both buckets and makes only `submissions-derived/public/` anonymously readable. The MinIO console is at http://localhost:9001, and the root user/password are `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` (default `pgw-dev` / `pgw-dev-secret`). The MinIO image is the community build `pgsty/minio`, pinned in `docker-compose.yml`.

## 3. Job contract (core → worker)

- **Stream:** `REDIS_JOB_STREAM` (`submissions`). Core only `XADD`s to it. The worker owns its consumer group (`pipeline`) and creates it (`XGROUP CREATE … MKSTREAM`).
- **Entry:** a flat field map. Every value is a string, because that is all a stream entry can hold.

| Field | Example | Meaning |
|---|---|---|
| `version` | `"1"` | Job contract version. Bumped only for a breaking change. |
| `job_id` | `"5f0c…"` (UUID) | Unique per job. **Echo it back in the result**: core applies only a result whose `job_id` matches the one it is waiting for. |
| `type` | `"media.image"` | `media.image`, `media.panorama` or `media.video`. |
| `submission_id` | `"9b1e…"` (UUID) | The media item's id. Also the `{mediaId}` in every key below. |
| `object_key` | `"9b1e…"` | The original's key in the staging bucket. Currently the media id itself. Read it from this field; don't derive it. |
| `content_type` | `"image/jpeg"` | What the uploader **declared**. Untrusted; see §4. |
| `declared_bytes` | `"2481152"` | Declared size, as a decimal string. Storage already enforced it on upload. |
| `declared_sha256` | `"n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg="` | Declared SHA-256 as **standard padded base64** (44 chars, `+/` alphabet, one trailing `=`). Not hex. |

**Unknown `version` or unknown `type`:** don't process the job. Publish a `failed` result naming the problem in `error`, then ack. Don't retry it. Core shows the uploader `processing_error`.

**Suggested Go shape** (described here only; nothing in `submission-pipeline/` has been changed):

```go
const (
    JobTypeMediaImage    JobType = "media.image"
    JobTypeMediaPanorama JobType = "media.panorama"
    JobTypeMediaVideo    JobType = "media.video"
)

type Job struct {
    Version        string // "version"
    ID             string // "job_id"
    Type           JobType
    SubmissionID   string
    ObjectKey      string
    ContentType    string // "content_type"
    DeclaredBytes  int64  // "declared_bytes", parsed from decimal
    DeclaredSHA256 string // "declared_sha256", base64
}
```

## 4. Required processing steps

Apply these in order. The first one that fails decides the result.

1. **Check the size.** Stat the staging object. If it is larger than `WORKER_MAX_FILE_BYTES`, the result is `rejected` / `dimensions_too_large`. This should not happen, because core caps uploads at 95 MiB (25 MiB for images).
2. **Recompute SHA-256** over the object's bytes and base64-encode it (standard, padded). If it doesn't equal `declared_sha256`, the result is `rejected` / `checksum_mismatch`. Your value is the authoritative one: core stores it and uses it for duplicate detection.
3. **Detect the real type from the magic bytes. Never trust `content_type`.** The allowed types for `media.image` and `media.panorama` are JPEG, PNG and WebP. Anything else is `rejected` / `unsupported_format`. A real type that differs from the declared one is not a rejection by itself, as long as the real type is allowed: process the file as what it really is.
4. **Enforce decode limits before a full decode** (protection against decompression bombs). Read the dimensions from the header. Refuse to decode above a pixel budget, for example 60 MP for images and 135 MP (16384×8192) for panoramas; agree the final numbers with core's owner. Refusal is `rejected` / `dimensions_too_large`. A file that can't be decoded is `rejected` / `corrupt_file`.
5. **PANORAMA_360 only: require 2:1** (width ÷ height within 2 ± 0.01). Otherwise `rejected` / `panorama_aspect_ratio`.
6. **Apply EXIF orientation to the pixels, then strip all metadata** (EXIF, XMP, IPTC, ICC may be kept or converted to sRGB). No variant may carry any metadata. The server never reads or stores EXIF (GPS and camera serials are privacy-sensitive). If EXIF-based suggestions ever exist, they happen only in the browser, off by default. Rotate before stripping, or portrait photos come out sideways.
7. **Write the variants** (§5) under `pending/{mediaId}/` in the media bucket.
8. **Publish the result** (§6).
9. **Delete the original** from the staging bucket.

The order of steps 7–9 matters. If the worker crashes after writing variants but before publishing, the retry overwrites the same keys. If it crashes after publishing but before deleting, a stray original is left behind; core's cleanup sweep removes it. Never delete the original before the result is published.

## 5. Variants and key layout

- **Keys:** `pending/{mediaId}/{size}.{ext}` in the media bucket, e.g. `pending/9b1e…/1280.webp`. Core **refuses** an `ok` result that has any key outside `pending/{mediaId}/` (or containing `..` or `//`). It treats that result as `processing_error` and stores nothing. When a moderator approves an item, core moves every variant to `public/{mediaId}/…` (same file names) and then deletes the whole `pending/{mediaId}/` prefix, so a published file is stored once. Core also deletes that prefix when an item is removed. So everything under that prefix must belong to that item alone.
- **Content-Type:** set the real type on every variant object you write (`image/webp`, `image/jpeg`, `image/ktx2`, …). Core's public copy keeps it, and a browser will not render an image served as `application/octet-stream`. Do not set `Cache-Control`; core sets it on the public copies (one year, immutable) and on signed URLs (`private, no-store`).
- **Never upscale.** Skip any size larger than the source. A result must still carry at least one variant: a source smaller than the smallest size gets one variant at its own size.
- **IMAGE:** responsive widths, e.g. `320`, `640`, `1280`, `1920`, `2560` (the `size` label is the width), WebP; JPEG is acceptable.
- **PANORAMA_360:** `2k` (2048×1024), `4k` (4096×2048), `8k` (8192×4096). KTX2 is the target; JPEG or WebP is acceptable until KTX2 lands. `format` says which one it is (`ktx2`, `webp`, `jpeg`).
- **VIDEO (later phase):** HLS renditions plus a poster frame. Not in the current contract. Adding `ffmpeg` or `basisu` to the worker image must be agreed with core's owner first.
- **Placeholder:** a tiny blurred preview (about 16–32 px wide) returned **inline in the result** as a `data:` URI. It is not a stored object. Core accepts only `data:image/(jpeg|png|webp|gif);base64,…` up to 16 KiB. SVG is refused.
- Field limits core enforces: `size` and `format` are non-empty, at most 40 chars; `bytes`, `width` and `height` are > 0; 1–32 variants per result.

## 6. Result contract (worker → core)

- **Stream:** `REDIS_RESULT_STREAM` (`submission-results`). The worker `XADD`s. Core reads with its own consumer group (`core`), which core creates. The worker should trim on write: `XADD submission-results MAXLEN ~ 10000 * result <json>`.
- **Entry:** exactly one field, **`result`**, whose value is the JSON of the existing `queue.Result`. The media payload is a new `media` member alongside `tabular`/`image`/`geo`. Media jobs don't use the existing `image` / `Variant{label}` shapes.
- **`version`** is the result contract version, the integer `1`. Core drops a result with any other version (logged; the item then times out).

```go
// Added to Result:
Media *MediaResult `json:"media,omitempty"` // set only for media.* jobs with status "ok"

type MediaResult struct {
    SHA256      string         `json:"sha256"`      // recomputed, standard padded base64
    Width       int            `json:"width"`       // of the original, after orientation
    Height      int            `json:"height"`
    Placeholder string         `json:"placeholder"` // data: URI, ≤ 16 KiB
    Variants    []MediaVariant `json:"variants"`
}

type MediaVariant struct {
    Size   string `json:"size"`   // "640", "2k", …
    Format string `json:"format"` // "webp", "jpeg", "ktx2", …
    Key    string `json:"key"`    // pending/{mediaId}/…
    Bytes  int64  `json:"bytes"`
    Width  int    `json:"width"`
    Height int    `json:"height"`
}
```

Example `ok` result (the value of the `result` field):

```json
{
  "version": 1,
  "job_id": "5f0c2a4e-6f0b-4d5e-9a57-1f2d3c4b5a69",
  "submission_id": "9b1e7c3a-2d4f-4e8b-a1c6-7f5e3d2b1a09",
  "type": "media.image",
  "status": "ok",
  "attempt": 1,
  "duration_ms": 1840,
  "finished_at": "2026-09-28T12:00:00Z",
  "media": {
    "sha256": "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=",
    "width": 4032,
    "height": 3024,
    "placeholder": "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==",
    "variants": [
      { "size": "640",  "format": "webp", "key": "pending/9b1e7c3a-2d4f-4e8b-a1c6-7f5e3d2b1a09/640.webp",  "bytes": 48213,  "width": 640,  "height": 480 },
      { "size": "1280", "format": "webp", "key": "pending/9b1e7c3a-2d4f-4e8b-a1c6-7f5e3d2b1a09/1280.webp", "bytes": 151022, "width": 1280, "height": 960 }
    ]
  }
}
```

`type` must echo the job's `type`. `rejected` carries `reason: { "code": "…", "detail": { … } }` and no `media`. `failed` carries `error` (operator-facing, never shown to users) and no `media`.

### When to send which status

- **`ok`**: every step in §4 passed and the variants are written.
- **`rejected`**: the file itself is unacceptable. This is terminal: never retried, and the uploader is shown why. The worker must use a code from the list below.
- **`failed`**: the worker itself couldn't do the job (storage unreachable, timeout, out of disk, unknown job version/type). **Retry internally first**, with backoff, and send `failed` only once you have given up. Core never retries a `failed` item. Keep the total retry window well under **60 minutes**: core fails any item still waiting after 60 minutes (checked hourly) with `processing_timeout` and ignores any later result.

Delivery is at-least-once in both directions. Sending the same result twice is harmless: core ignores a result for an item that is no longer waiting, or whose `job_id` it isn't waiting for.

### Rejection reason codes

These are the only codes core accepts from the worker. Each one has translated messages in fa/en/ar.

| Code | When |
|---|---|
| `unsupported_format` | Magic bytes are not an allowed type for the job type. |
| `corrupt_file` | The file can't be decoded. |
| `dimensions_too_large` | Over the decode/pixel budget, or over `WORKER_MAX_FILE_BYTES`. |
| `checksum_mismatch` | The recomputed SHA-256 differs from `declared_sha256`. |
| `panorama_aspect_ratio` | A `media.panorama` that is not 2:1. |

`reason.detail` values are logged but not shown. Messages don't interpolate them. **Any new code must be agreed with core's owner first**, because core needs translations for it. Core stores an unknown code as the generic `rejected`, and does the same for a code core reserves for itself (`upload_missing`, `upload_mismatch`, `duplicate`, `processing_error`, `processing_timeout`, `rejected`).

### What core does with each outcome

| Result | Item becomes | Also |
|---|---|---|
| `ok`, payload valid | READY | Stores `sha256`, variants and placeholder; opens a moderation task per metadata version awaiting review. |
| `ok`, same verified `sha256` already live in the same article | FAILED `duplicate` | Variants are left for core's cleanup sweep. |
| `ok`, payload invalid (bad key, hash, placeholder, type mismatch, …) | FAILED `processing_error` | Nothing stored; logged as an error. |
| `rejected` | FAILED with `reason.code` | |
| `failed` | FAILED `processing_error` | `error` logged. |
| no result within 60 min | FAILED `processing_timeout` | A later result is ignored. |

Core deletes FAILED items, together with their staging original and everything under `pending/{mediaId}/`, after 7 days.

## 7. Phase order

1. **Now:** `media.image` and `media.panorama`, with JPEG/WebP variants.
2. **Later:** KTX2 panorama variants (`basisu`).
3. **Later:** `media.video`: HLS renditions and a poster frame (`ffmpeg`). The video result payload will be added to this document before any work starts.

## 8. Testing locally

The worker can be exercised without core running.

```sh
docker compose up -d redis minio minio-init

# Put an original in staging under a made-up media id.
ID=$(uuidgen)
mc alias set local http://localhost:9000 pgw-dev pgw-dev-secret
mc cp photo.jpg "local/submissions-raw/$ID"
SHA=$(openssl dgst -sha256 -binary photo.jpg | base64)
BYTES=$(stat -c %s photo.jpg)

# Queue a job exactly as core does.
redis-cli XADD submissions '*' version 1 job_id "$(uuidgen)" type media.image \
  submission_id "$ID" object_key "$ID" content_type image/jpeg \
  declared_bytes "$BYTES" declared_sha256 "$SHA"

# Read what the worker reported, and check what it wrote.
redis-cli XRANGE submission-results - +
mc ls "local/submissions-derived/pending/$ID/"
```

To see core apply a result, run core (`SPRING_PROFILES_ACTIVE=dev`) against the same stack and upload through the real API (reserve → PUT → complete). A made-up media id is ignored by core, as is a PROCESSING item minted by the dev fixture endpoint (`/api/dev/test-media`), because it has no job id. Both are logged at info. Core's own tests play the worker by writing results onto the stream (`MediaProcessingFlowIntegrationTests`). They never run the worker.
