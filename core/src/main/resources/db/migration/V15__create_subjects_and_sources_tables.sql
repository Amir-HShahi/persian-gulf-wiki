-- The Subject taxonomy and the citation catalogue: the two root tables the rest of the
-- wiki content domain hangs off. Article.subject_id (a later migration) points at
-- subjects; measurements, claims and fact provenance point at sources.

-- A Subject is a deliberately bare node. It carries no name column: names are per-language
-- and live in the `labels` table (referent_kind = 'SUBJECT'), so putting a canonical name
-- here would create a second source of truth that the fa/en/ar labels could disagree with.
-- Structured facts live in subject_measurements/subject_claims, prose lives in articles,
-- and the typed columns below live in the per-kind subtype tables. What is left is
-- identity and kind.
CREATE TABLE subjects (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind       VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Non-null only on rows minted by POST /api/dev/test-subjects. Unlike users -- where
    -- the e2e- prefix rides along on an email that had to exist anyway -- a subject has no
    -- natural string field to carry a marker, and overloading `kind` would corrupt the
    -- taxonomy. The dev DELETE route and DevTestSubjectSweeper both refuse to touch a row
    -- whose marker is NULL, which is what makes handing an arbitrary id to that route safe.
    dev_marker VARCHAR(40),
    CONSTRAINT ck_subjects_kind CHECK (kind IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES'))
);

CREATE INDEX idx_subjects_kind ON subjects (kind);
CREATE INDEX idx_subjects_dev_marker ON subjects (dev_marker) WHERE dev_marker IS NOT NULL;

-- The four typed subtypes. Each shares subjects.id as its own primary key rather than
-- carrying a surrogate id of its own, so the relationship is 1:1 by construction and an
-- island can never end up attached to two subjects. ON DELETE CASCADE means deleting the
-- subject takes its detail row with it -- there is no such thing as an orphaned island.
--
-- SRID 4326 (WGS84 lat/lng) throughout, declared in the column type rather than left to
-- whatever the inserting client happens to send: a mixed-SRID column silently breaks every
-- distance and containment query later, and Postgres will not let you fix it in place once
-- rows exist.
CREATE TABLE islands (
    subject_id UUID PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    area_km2   NUMERIC(12, 4),
    location   geometry(Point, 4326)
);

CREATE TABLE ports (
    subject_id UUID PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    location   geometry(Point, 4326)
);

CREATE TABLE oil_fields (
    subject_id UUID PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    area       geometry(Polygon, 4326)
);

CREATE TABLE species (
    subject_id UUID PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    habitat    geometry(Polygon, 4326)
);

-- Additional geometries attached to a subject beyond the single one its subtype row holds:
-- an island's reef outline alongside its centroid, a port's approach channel alongside its
-- berth. Many-per-subject, hence a surrogate id here rather than the shared-PK pattern
-- above, and an untyped geometry column since the shape varies by what is being recorded.
CREATE TABLE subject_geometries (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id UUID NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    geom       geometry(Geometry, 4326) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_subject_geometries_subject ON subject_geometries (subject_id);
-- GiST, not btree: btree cannot answer the bounding-box overlap question every spatial
-- predicate (ST_Intersects, ST_DWithin, ST_Contains) is planned as.
CREATE INDEX idx_subject_geometries_geom ON subject_geometries USING GIST (geom);

-- A citable work -- the thing a measurement, claim or fact provenance row points at to say
-- where a number came from. Independent of subjects: one survey report is cited by many
-- islands, and a source is worth recording before anything cites it.
CREATE TABLE sources (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title              TEXT NOT NULL,
    url                TEXT,
    publisher          TEXT,
    published_on       DATE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    -- Same role as subjects.dev_marker. Deliberately not a prefix on `title`: a genuine
    -- source really can be titled "e2e-something", and a delete route that keys on the
    -- title of a row a contributor typed is a delete route that eventually removes it.
    dev_marker         VARCHAR(40)
);

CREATE INDEX idx_sources_created_by ON sources (created_by_user_id);
CREATE INDEX idx_sources_dev_marker ON sources (dev_marker) WHERE dev_marker IS NOT NULL;
