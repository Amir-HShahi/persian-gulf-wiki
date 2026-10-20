-- Phase 7: soft delete, merge and the duplicate lookup.

-- Set by a moderator's soft delete (reversible) or by a merge (not reversible: the subject then has a
-- row in subject_redirects). A deleted subject is hidden from every public read -- listings, facets,
-- the infobox, references from other subjects' infoboxes -- but its row, labels and revision history
-- stay, which is what makes a restore lossless. Its open revisions can no longer be submitted or
-- approved.
ALTER TABLE subjects ADD COLUMN deleted_at TIMESTAMPTZ;

-- Where a merged-away subject now lives. Anything still naming the source -- a link in an article
-- body, a SUBJECT_REF value in a revision payload, a bookmarked GET /api/subjects/{id} -- is resolved
-- through this table rather than rewritten, because bodies and approved payloads are history.
--
-- Chains are flattened when they would form (merging B into C repoints every A -> B row at C), so a
-- lookup is always one hop and the target is never itself a merged subject.
--
-- CASCADE on both ends: the source row is kept by a merge, so only a hard delete (admin) or the dev
-- sweepers remove either end, and neither should be blocked by a redirect. Losing the redirect when
-- its target is hard-deleted is right: there is nothing left to redirect to.
CREATE TABLE subject_redirects (
    source_id UUID PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    target_id UUID NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    merged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_subject_redirects_not_self CHECK (source_id <> target_id)
);

-- For flattening (every redirect pointing at a subject that is itself being merged away).
CREATE INDEX idx_subject_redirects_target ON subject_redirects (target_id);

-- The comparison key for GET /api/subjects/lookup: case- and diacritic-insensitive, with the Arabic
-- and Persian forms of the same letter folded together, so "Qeshm", "qéshm", "قشم" typed with an
-- Arabic keyboard (ي, ك) and "جزيرة" against "جزیره" all meet.
--
-- In order: lower-case; NFKD, which splits accents and hamza/madda off their letters (é -> e + ´,
-- أ -> ا + ٔ, ۀ -> ە + ٔ) and unfolds Arabic presentation forms and full-width Latin; drop every
-- combining mark (Latin accents, Arabic harakat and hamza/madda, Quranic marks), tatweel, zero-width
-- and bidi controls, whitespace and common punctuation -- so "نیم‌فاصله", "نیم فاصله" and "نیمفاصله"
-- compare equal; then fold ي/ى -> ی, ك -> ک, ة/ە/ۃ -> ه, and Arabic-Indic and Persian digits -> ASCII.
--
-- A plain SQL function rather than the unaccent extension: unaccent knows Latin accents but not
-- Arabic marks, so the marks need this regex anyway, and NFKD already covers what unaccent would add.
-- IMMUTABLE, so an expression index can use it if the lookup ever needs one; at this scale it scans.
CREATE FUNCTION subject_search_key(value TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN translate(
        regexp_replace(
            normalize(lower(value), NFKD),
            '[[:space:]̀-ͯؐ-ؚـً-ٰٟۖ-ۭ​-‏‪-‮⁦-⁩.,;:!?''"’ʼ`()/\\،؛؟«»_-]',
            '', 'g'),
        'يىكةەۃ٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹',
        'ییکههه01234567890123456789');
