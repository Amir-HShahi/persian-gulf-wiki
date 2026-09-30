-- Retires the "entity type" vocabulary in favour of SubjectKind. An article no longer stores a
-- kind at all: the API's subjectKind is read off its subject, and an article with no subject has
-- none -- GENERIC is gone. Roles and expert-reviewer applications keep their column, renamed to
-- expertise and narrowed from free-form text to SubjectKind names.

-- Shared by the user_roles and expert_reviewer_applications sections below: entity_type was
-- free-form there (V11, V13), so existing values are mapped onto SubjectKind names rather than
-- copied. Trimmed, CamelCase split at each lower-to-upper change, every other run of
-- non-alphanumerics turned into "_", upper-cased: "Species" -> SPECIES, "OilField",
-- "oil field" and "oil-field" -> OIL_FIELD. Dropped at the end of this migration.
CREATE FUNCTION pg_temp.v20_to_subject_kind(entity_type TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS $$
SELECT btrim(upper(regexp_replace(
        regexp_replace(btrim(entity_type), '([a-z0-9])([A-Z])', '\1_\2', 'g'),
        '[^A-Za-z0-9]+', '_', 'g')), '_')
$$;

-- articles.entity_type ------------------------------------------------------------------------

-- Dropping the column is lossless only where entity_type was derivable: from the subject when
-- subject_id is set, GENERIC when it isn't. A subject-less article with any other value (the dev
-- fixture endpoint allowed e.g. STRAIT) holds a kind the API can no longer express, so a real
-- one aborts the migration instead of being silently flattened. Dev-marked rows are exempt:
-- they're throwaway fixtures the sweeper deletes anyway, and one left over on a dev-profile
-- staging box must not block a deploy.
DO $$
DECLARE
    lossy_count BIGINT;
BEGIN
    SELECT count(*) INTO lossy_count
    FROM articles
    WHERE subject_id IS NULL AND entity_type <> 'GENERIC' AND dev_marker IS NULL;
    IF lossy_count > 0 THEN
        RAISE EXCEPTION 'V20: % subject-less article(s) have a non-GENERIC entity_type that dropping the column would lose; bind each to a subject or set it to GENERIC, then rerun', lossy_count;
    END IF;
END $$;

DROP INDEX idx_articles_entity_type;
ALTER TABLE articles DROP CONSTRAINT ck_articles_entity_type;
ALTER TABLE articles DROP COLUMN entity_type;

-- user_roles.entity_type -> expertise ---------------------------------------------------------

-- A value that doesn't map onto a SubjectKind aborts the migration rather than being dropped or
-- guessed at: either would silently change which subjects an expert reviewer may verify. Two
-- spellings of one kind held by the same user (e.g. "Species" and "SPECIES") collapse into a
-- duplicate and fail on uq_user_roles_user_id_role_entity_type below, which is just as loud.
DO $$
DECLARE
    unmapped TEXT;
BEGIN
    SELECT string_agg(DISTINCT quote_literal(entity_type), ', ') INTO unmapped
    FROM user_roles
    WHERE entity_type IS NOT NULL
      AND pg_temp.v20_to_subject_kind(entity_type) NOT IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES');
    IF unmapped IS NOT NULL THEN
        RAISE EXCEPTION 'V20: user_roles.entity_type values with no SubjectKind to map to: %', unmapped;
    END IF;
END $$;

UPDATE user_roles
SET entity_type = pg_temp.v20_to_subject_kind(entity_type)
WHERE entity_type IS NOT NULL;

ALTER TABLE user_roles RENAME COLUMN entity_type TO expertise;
ALTER TABLE user_roles
    RENAME CONSTRAINT uq_user_roles_user_id_role_entity_type TO uq_user_roles_user_id_role_expertise;
-- uq_user_roles_global keeps its name: it names no column, and Postgres rewrites its
-- "WHERE entity_type IS NULL" predicate to the renamed column on its own.
--
-- Mirrors SubjectKind, same maintenance contract as subjects.kind's CHECK: a new kind means
-- widening this alongside it. NULL (a global role) passes, as before.
ALTER TABLE user_roles
    ADD CONSTRAINT ck_user_roles_expertise
        CHECK (expertise IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES'));

-- expert_reviewer_applications.entity_type -> expertise ---------------------------------------

-- Same mapping and same refusal as user_roles. An approved application's expertise is what its
-- granted role was scoped to, so the two must agree after the rename.
DO $$
DECLARE
    unmapped TEXT;
BEGIN
    SELECT string_agg(DISTINCT quote_literal(entity_type), ', ') INTO unmapped
    FROM expert_reviewer_applications
    WHERE pg_temp.v20_to_subject_kind(entity_type) NOT IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES');
    IF unmapped IS NOT NULL THEN
        RAISE EXCEPTION 'V20: expert_reviewer_applications.entity_type values with no SubjectKind to map to: %',
            unmapped;
    END IF;
END $$;

UPDATE expert_reviewer_applications
SET entity_type = pg_temp.v20_to_subject_kind(entity_type);

-- uq_expert_reviewer_applications_pending follows the rename on its own, like uq_user_roles_global.
ALTER TABLE expert_reviewer_applications RENAME COLUMN entity_type TO expertise;
ALTER TABLE expert_reviewer_applications
    ADD CONSTRAINT ck_expert_reviewer_applications_expertise
        CHECK (expertise IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES'));

DROP FUNCTION pg_temp.v20_to_subject_kind(TEXT);
