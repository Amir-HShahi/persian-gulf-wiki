-- An island's area is now the `area` fact (V23), written by subject revision approval. Keeping
-- islands.area_km2 alongside it would be two sources of truth that drift apart the first time
-- one of them is edited, so the column goes.
--
-- Only the removed moderator create route ever wrote it, and its values carry no source, so they
-- cannot become facts automatically. A real value aborts the migration instead of vanishing:
-- re-enter it as an `area` fact through a subject revision (with its source), clear the column,
-- then rerun. Dev-marked rows are exempt, as in V20 -- they are throwaway fixtures.
DO $$
DECLARE
    valued_count BIGINT;
BEGIN
    SELECT count(*) INTO valued_count
    FROM islands i
    JOIN subjects s ON s.id = i.subject_id
    WHERE i.area_km2 IS NOT NULL AND s.dev_marker IS NULL;
    IF valued_count > 0 THEN
        RAISE EXCEPTION 'V25: % island(s) still have an area_km2 value that dropping the column would lose; record each as an area fact through a subject revision, set area_km2 to NULL, then rerun', valued_count;
    END IF;
END $$;

ALTER TABLE islands DROP COLUMN area_km2;
