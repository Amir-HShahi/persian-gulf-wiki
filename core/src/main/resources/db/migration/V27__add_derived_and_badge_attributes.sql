-- Two kinds of field the vocabulary could not describe before the read side existed.
--
-- is_derived: a field whose value is computed at read time and never typed into a revision --
-- population_density from population and area, has_360_tour from the subject's main article's
-- gallery. They are definitions rather than constants in code so that their names live in
-- `labels` like every other field's (an admin relabels them through PUT /api/admin/labels), and so
-- that GET /api/subject-kinds/{kind}/attributes lists has_360_tour for the filter panel. Only a
-- migration sets the flag: the code that computes a derived value is keyed by the field's `key`,
-- so a derived field created through the API would have nothing computing it.
--
-- is_badge: a field whose code is shown on a subject's listing card, such as an IUCN status chip.
-- Admin-settable like the other display flags.
ALTER TABLE attribute_definitions
    ADD COLUMN is_derived BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN is_badge   BOOLEAN NOT NULL DEFAULT false;

-- A card shows one code per badge, so a badge is a single-valued ENUM.
ALTER TABLE attribute_definitions
    ADD CONSTRAINT ck_attribute_definitions_badge_single_enum
        CHECK (NOT is_badge OR (value_type = 'ENUM' AND NOT is_multi));

-- No payload may carry a derived field, so a required one would refuse every revision of its kinds.
ALTER TABLE attribute_definitions
    ADD CONSTRAINT ck_attribute_definitions_derived_not_required
        CHECK (NOT (is_derived AND is_required));

-- has_360_tour applies to every kind, so it cannot sit in a species group or a places group; it
-- gets one of its own, ordered after both sets (V23 uses 10-70).
INSERT INTO attribute_groups (key, sort) VALUES ('media', 80);

INSERT INTO labels (group_id, language, value)
SELECT g.id, v.language, v.value
FROM (VALUES
    ('media', 'fa', 'رسانه'),
    ('media', 'en', 'Media'),
    ('media', 'ar', 'الوسائط')
) AS v (key, language, value)
JOIN attribute_groups g ON g.key = v.key;

-- population_density sorts right after population (10) in demography. has_360_tour is a filter
-- only; the infobox has the gallery itself to show.
INSERT INTO attribute_definitions
    (key, value_type, unit, group_id, sort, is_multi, is_required, is_infobox, is_filterable, is_derived)
SELECT v.key, v.value_type, v.unit, g.id, v.sort, false, false, v.is_infobox, v.is_filterable, true
FROM (VALUES
    ('population_density', 'NUMBER', 'per_km2', 'demography', 15, true, false),
    ('has_360_tour', 'BOOL', NULL::VARCHAR, 'media', 10, false, true)
) AS v (key, value_type, unit, group_key, sort, is_infobox, is_filterable)
JOIN attribute_groups g ON g.key = v.group_key;

-- population_density only where both of its inputs apply: population is on ISLAND and PORT, area
-- on ISLAND, FISHING_GROUND and OIL_FIELD (V23). An admin giving PORT an area should add PORT here
-- too.
INSERT INTO attribute_definition_kinds (attribute_id, kind)
SELECT d.id, v.kind
FROM (VALUES
    ('population_density', 'ISLAND'),
    ('has_360_tour', 'ISLAND'),
    ('has_360_tour', 'PORT'),
    ('has_360_tour', 'OIL_FIELD'),
    ('has_360_tour', 'SPECIES'),
    ('has_360_tour', 'STRAIT'),
    ('has_360_tour', 'FISHING_GROUND'),
    ('has_360_tour', 'PROVINCE')
) AS v (key, kind)
JOIN attribute_definitions d ON d.key = v.key;

-- has_360_tour's label doubles as its only filter option's label (a BOOL facet offers just
-- "true"), so it reads as a checkbox.
INSERT INTO labels (attribute_id, language, value)
SELECT d.id, v.language, v.value
FROM (VALUES
    ('population_density', 'fa', 'تراکم جمعیت'),
    ('population_density', 'en', 'Population density'),
    ('population_density', 'ar', 'الكثافة السكانية'),
    ('has_360_tour', 'fa', 'دارای تور مجازی ۳۶۰ درجه'),
    ('has_360_tour', 'en', 'Has a 360° tour'),
    ('has_360_tour', 'ar', 'تتوفر جولة افتراضية بزاوية 360 درجة')
) AS v (key, language, value)
JOIN attribute_definitions d ON d.key = v.key;

UPDATE attribute_definitions SET is_badge = true WHERE key = 'iucn_status';

-- Sanity check ---------------------------------------------------------------------------------

-- Same purpose as V23's: a mistyped key above drops rows silently, so count what must exist.
DO $$
DECLARE
    unlabeled BIGINT;
    derived   BIGINT;
    badges    BIGINT;
BEGIN
    SELECT (SELECT count(*) FROM attribute_groups g
            WHERE g.key = 'media' AND (SELECT count(*) FROM labels l WHERE l.group_id = g.id) <> 3)
         + (SELECT count(*) FROM attribute_definitions d
            WHERE d.is_derived AND (SELECT count(*) FROM labels l WHERE l.attribute_id = d.id) <> 3)
    INTO unlabeled;
    SELECT count(*) INTO derived
    FROM attribute_definitions d
    WHERE d.is_derived AND EXISTS (SELECT 1 FROM attribute_definition_kinds k WHERE k.attribute_id = d.id);
    SELECT count(*) INTO badges FROM attribute_definitions WHERE key = 'iucn_status' AND is_badge;
    IF unlabeled > 0 OR derived <> 2 OR badges <> 1 THEN
        RAISE EXCEPTION 'V27: % referent(s) without three labels, % derived field(s) with a kind (want 2), % badge(s) on iucn_status',
            unlabeled, derived, badges;
    END IF;
END $$;
