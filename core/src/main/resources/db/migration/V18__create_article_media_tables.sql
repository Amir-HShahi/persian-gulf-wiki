-- Article gallery: user-contributed images, videos and 360° panoramas, similar to a map
-- place's photo strip. A gallery item belongs to the *article*, not to a translation or a
-- revision -- it has no language of its own, and uploading one never creates or touches an
-- article revision.
--
-- The split between the two main tables mirrors articles' translations/revisions split
-- (V16): article_media is the file and its lifecycle, media_metadata_versions is the
-- moderated, append-only history of what is said *about* the file (when/where it was shot,
-- which way it faces, descriptions). The public sees exactly one metadata version per item,
-- the one current_metadata_version_id points at, and a later edit never disturbs it until a
-- moderator approves the edit.

CREATE TABLE article_media (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    article_id                   UUID NOT NULL REFERENCES articles (id) ON DELETE CASCADE,
    -- IMAGE / VIDEO / PANORAMA_360. The CHECK list mirrors MediaKind exactly -- a new value
    -- means a migration alongside the enum, same contract as every enum-backed column here.
    type                         VARCHAR(20) NOT NULL,
    -- NOT NULL with the default RESTRICT, not ON DELETE SET NULL: this is the authorization
    -- key for /complete and for the uploader's own reads of a pending item, same reasoning as
    -- article_revisions.author_id in V16 -- nulling it would silently strip the check rather
    -- than fail loudly.
    uploaded_by                  UUID NOT NULL REFERENCES users (id),
    -- What the client *said* it would upload, recorded at reservation time. Untrusted: the
    -- presigned PUT is signed over these three, so storage rejects a different file, but the
    -- pipeline still recomputes the hash itself and its value (sha256 below) is authoritative.
    declared_content_type        VARCHAR(100) NOT NULL,
    declared_bytes               BIGINT NOT NULL,
    -- Base64 of the 32-byte digest (44 chars), the encoding x-amz-checksum-sha256 uses, so
    -- the value round-trips to storage and back without re-encoding.
    declared_sha256              VARCHAR(44) NOT NULL,
    -- Verified hash, written by the pipeline's result (Phase 2). Null until then.
    sha256                       VARCHAR(44),
    -- UPLOADING / PROCESSING / READY / FAILED -- where the *file* is. Independent of
    -- publication_status below: an item is public only when this is READY *and* that is
    -- PUBLISHED.
    processing_status            VARCHAR(20) NOT NULL,
    -- Stable, localisable reason for FAILED (e.g. upload_mismatch, upload_missing, and the
    -- pipeline's own rejection codes). The frontend translates it; it is never free text.
    failure_code                 VARCHAR(60),
    -- PENDING / PUBLISHED / REJECTED / HIDDEN -- whether the public may see it.
    publication_status           VARCHAR(20) NOT NULL,
    -- When the item first became PUBLISHED; the public gallery's sort key. Null until then.
    published_at                 TIMESTAMPTZ,
    -- Deferred FK, added below once media_metadata_versions exists -- same cyclic-reference
    -- shape as article_translations.current_revision_id in V16.
    current_metadata_version_id  UUID,
    -- [{ size, format, key, bytes, width, height }], written by the pipeline. jsonb rather
    -- than a child table: the list is written once as a unit, read as a unit, and never
    -- queried into.
    variants                     JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- Tiny base64 data: URI shown while the real variant loads. Null until processed.
    placeholder                  TEXT,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as articles.dev_marker (V16): non-null only on rows minted by the dev-profile
    -- fixture endpoint, which is what makes handing an arbitrary id to the matching DELETE
    -- route safe.
    dev_marker                   VARCHAR(40),
    CONSTRAINT ck_article_media_type
        CHECK (type IN ('IMAGE', 'VIDEO', 'PANORAMA_360')),
    CONSTRAINT ck_article_media_processing_status
        CHECK (processing_status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT ck_article_media_publication_status
        CHECK (publication_status IN ('PENDING', 'PUBLISHED', 'REJECTED', 'HIDDEN')),
    CONSTRAINT ck_article_media_declared_bytes CHECK (declared_bytes > 0),
    -- A published item always has something to show about itself. Enforced here rather than
    -- only in MediaModerationService so the dev fixture endpoint, which bypasses it on
    -- purpose, cannot mint a PUBLISHED row the read path would then have to special-case.
    CONSTRAINT ck_article_media_published_has_metadata
        CHECK (publication_status <> 'PUBLISHED'
            OR (current_metadata_version_id IS NOT NULL AND published_at IS NOT NULL))
);

CREATE INDEX idx_article_media_article ON article_media (article_id);
-- The anti-abuse pending-cap and quota checks both filter by uploader.
CREATE INDEX idx_article_media_uploaded_by ON article_media (uploaded_by);
-- The public gallery page: one article's published items, newest approval first.
CREATE INDEX idx_article_media_public
    ON article_media (article_id, published_at DESC)
    WHERE processing_status = 'READY' AND publication_status = 'PUBLISHED';
-- The abandoned-upload and rejected-item sweeps both scan by status and age.
CREATE INDEX idx_article_media_processing_status ON article_media (processing_status, created_at);
CREATE INDEX idx_article_media_publication_status ON article_media (publication_status, updated_at);
-- The reservation-time duplicate pre-check, which runs on the declared hash because the
-- verified one does not exist yet.
CREATE INDEX idx_article_media_declared_sha256 ON article_media (article_id, declared_sha256);
CREATE INDEX idx_article_media_dev_marker ON article_media (dev_marker) WHERE dev_marker IS NOT NULL;

-- The same file may not appear twice in one article's gallery. On the *verified* hash, so it
-- can only fire when the pipeline reports (Phase 2), which turns a violation into FAILED with
-- failure_code 'duplicate'. REJECTED items are excluded so a rejected upload never blocks a
-- later, legitimate one of the same file. Per article, not global: the same photo of a strait
-- can legitimately illustrate the strait and a port on it.
CREATE UNIQUE INDEX uq_article_media_article_sha256
    ON article_media (article_id, sha256)
    WHERE sha256 IS NOT NULL AND publication_status <> 'REJECTED';

-- One moderated version of what is said about a media item. Append-only in the same sense as
-- article_revisions: a rejected or superseded version is never deleted, only left
-- unpointed-to by article_media.current_metadata_version_id.
--
-- Every metadata field is nullable on purpose. A contributor may skip any of them, and phone
-- GPS in particular is unreliable in this region (spoofing and jamming are common), so an
-- absent value is always preferred to a guessed one.
CREATE TABLE media_metadata_versions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    media_id         UUID NOT NULL REFERENCES article_media (id) ON DELETE CASCADE,
    -- 1-based per media item, allocated by the service, never client-supplied.
    version_number   INT NOT NULL,
    -- RESTRICT, same reasoning as article_revisions.author_id: this is who proposed the
    -- version, and the moderation record of that must not be silently erased.
    submitted_by     UUID NOT NULL REFERENCES users (id),
    -- PENDING_REVIEW / APPROVED / REJECTED. No CHANGES_REQUESTED: media moderation is
    -- APPROVE/REJECT only, because proposing a new version is the uploader's way to "fix" one.
    status           VARCHAR(20) NOT NULL,
    shot_at          TIMESTAMPTZ,
    -- The UTC offset in force where the shot was taken, as "+03:30"/"-05:00". Nullable and
    -- never inferred from location or from the uploader's timezone -- a wrong offset shifts
    -- the local time the gallery displays, which is worse than displaying none.
    shot_at_offset   VARCHAR(6),
    -- geography rather than geometry (contrast subjects in V15): these are single points
    -- compared by real-world distance ("photos near here"), which geography measures in
    -- metres without a projection choice.
    location         geography(Point, 4326),
    -- Approximate, as reported by the device or the contributor.
    altitude_m       NUMERIC(7, 1),
    heading_deg      NUMERIC(5, 2),
    -- TRUE / MAGNETIC north. Nullable even when heading_deg is set: "facing 120°, reference
    -- unknown" is still better than dropping the heading.
    heading_ref      VARCHAR(10),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_media_metadata_versions_media_number UNIQUE (media_id, version_number),
    CONSTRAINT ck_media_metadata_versions_status
        CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_media_metadata_versions_heading_range
        CHECK (heading_deg IS NULL OR (heading_deg >= 0 AND heading_deg < 360)),
    CONSTRAINT ck_media_metadata_versions_heading_ref
        CHECK (heading_ref IS NULL OR heading_ref IN ('TRUE', 'MAGNETIC')),
    -- An offset with no timestamp has nothing to be the offset of.
    CONSTRAINT ck_media_metadata_versions_offset_needs_shot_at
        CHECK (shot_at_offset IS NULL OR shot_at IS NOT NULL),
    CONSTRAINT ck_media_metadata_versions_offset_format
        CHECK (shot_at_offset IS NULL OR shot_at_offset ~ '^[+-](0[0-9]|1[0-4]):[0-5][0-9]$')
);

CREATE INDEX idx_media_metadata_versions_media ON media_metadata_versions (media_id);
CREATE INDEX idx_media_metadata_versions_shot_at ON media_metadata_versions (shot_at);
CREATE INDEX idx_media_metadata_versions_location ON media_metadata_versions USING GIST (location);

ALTER TABLE article_media
    ADD CONSTRAINT fk_article_media_current_metadata_version
        FOREIGN KEY (current_metadata_version_id) REFERENCES media_metadata_versions (id);

-- Per-language caption for one metadata version. language uses the same BCP-47 codes as
-- article_translations.language. Belongs to the version, not the item, so editing a
-- description is a new version and goes through moderation like any other metadata edit.
CREATE TABLE media_descriptions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    metadata_version_id  UUID NOT NULL REFERENCES media_metadata_versions (id) ON DELETE CASCADE,
    language             VARCHAR(20) NOT NULL,
    text                 TEXT NOT NULL,
    CONSTRAINT uq_media_descriptions_version_language UNIQUE (metadata_version_id, language)
);

-- A clickable hotspot inside a panorama, leading to another panorama (possibly in another
-- article). One-way. Belongs to the *source* item's metadata version, so moving or adding a
-- hotspot is a metadata edit and is moderated like one.
--
-- to_media_id is ON DELETE CASCADE, and that is not in tension with the rule that hiding or
-- rejecting a target must never delete links to it: HIDDEN/REJECTED are publication_status
-- values, not deletions, so they never fire this FK at all -- the read path filters such
-- links out instead, and they reappear if the target is restored. Only an actual row deletion
-- (the rejected-item sweep, an uploader/moderator delete) removes links, because at that
-- point there is nothing left to restore; RESTRICT would instead make every linked-to item
-- undeletable and abort the sweep's bulk delete.
--
-- Both ends being PANORAMA_360, no self-link and at most 20 links per version are enforced in
-- the service: each needs a lookup a CHECK cannot express.
CREATE TABLE panorama_links (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    metadata_version_id  UUID NOT NULL REFERENCES media_metadata_versions (id) ON DELETE CASCADE,
    to_media_id          UUID NOT NULL REFERENCES article_media (id) ON DELETE CASCADE,
    yaw_deg              NUMERIC(5, 2) NOT NULL,
    pitch_deg            NUMERIC(5, 2) NOT NULL,
    label                VARCHAR(200),
    CONSTRAINT ck_panorama_links_yaw CHECK (yaw_deg >= 0 AND yaw_deg < 360),
    CONSTRAINT ck_panorama_links_pitch CHECK (pitch_deg >= -90 AND pitch_deg <= 90)
);

CREATE INDEX idx_panorama_links_version ON panorama_links (metadata_version_id);
CREATE INDEX idx_panorama_links_to_media ON panorama_links (to_media_id);

-- Moderation tasks now judge one of two kinds of target: an article revision (V17) or a media
-- metadata version. Exactly one of the two columns is set on every row.
--
-- Two nullable FKs plus a CHECK, rather than a polymorphic (target_type, target_id) pair:
-- each column keeps a real foreign key, so ON DELETE CASCADE still removes a task together
-- with the thing it judges -- the V17 revision_id comment's correctness and sweeper reasoning
-- applies unchanged to the new column (article_media -> media_metadata_versions is a CASCADE
-- chain, so without CASCADE here a task would block deleting its media item, and the
-- abandoned-upload/rejected-item sweeps would abort on the first blocked row).
--
-- The uniqueness rule carries over too: at most one task per metadata version, ever. A
-- rejected version is terminal and another attempt is a new version with its own task, which
-- is exactly the V17 model for revisions. Postgres UNIQUE ignores NULLs, so each column's
-- constraint only ever compares rows of its own kind.
ALTER TABLE moderation_tasks ALTER COLUMN revision_id DROP NOT NULL;

ALTER TABLE moderation_tasks
    ADD COLUMN media_metadata_version_id UUID
        REFERENCES media_metadata_versions (id) ON DELETE CASCADE;

ALTER TABLE moderation_tasks
    ADD CONSTRAINT uq_moderation_tasks_media_metadata_version UNIQUE (media_metadata_version_id);

ALTER TABLE moderation_tasks
    ADD CONSTRAINT ck_moderation_tasks_exactly_one_target
        CHECK ((revision_id IS NULL) <> (media_metadata_version_id IS NULL));
