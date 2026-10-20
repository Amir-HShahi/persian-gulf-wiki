-- Three new SubjectKinds: STRAIT, FISHING_GROUND and PROVINCE. Each gets a shared-PK subtype
-- table like the four in V15, and every CHECK that mirrors SubjectKind is widened in the same
-- migration (see the SubjectKind enum for that maintenance contract).

ALTER TABLE subjects DROP CONSTRAINT ck_subjects_kind;
ALTER TABLE subjects
    ADD CONSTRAINT ck_subjects_kind
        CHECK (kind IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES', 'STRAIT', 'FISHING_GROUND', 'PROVINCE'));

-- Without these, an expert reviewer could never be scoped to one of the new kinds.
ALTER TABLE user_roles DROP CONSTRAINT ck_user_roles_expertise;
ALTER TABLE user_roles
    ADD CONSTRAINT ck_user_roles_expertise
        CHECK (expertise IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES', 'STRAIT', 'FISHING_GROUND', 'PROVINCE'));

ALTER TABLE expert_reviewer_applications DROP CONSTRAINT ck_expert_reviewer_applications_expertise;
ALTER TABLE expert_reviewer_applications
    ADD CONSTRAINT ck_expert_reviewer_applications_expertise
        CHECK (expertise IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES', 'STRAIT', 'FISHING_GROUND', 'PROVINCE'));

-- A strait and a fishing ground are both water extents, so Polygon, like oil_fields.area.
CREATE TABLE straits (
    subject_id UUID PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    area       geometry(Polygon, 4326)
);

CREATE TABLE fishing_grounds (
    subject_id UUID PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    area       geometry(Polygon, 4326)
);

-- MultiPolygon, not Polygon: a coastal province such as Hormozgan takes in its islands, so
-- its boundary is several disjoint rings that a single Polygon cannot hold.
CREATE TABLE provinces (
    subject_id UUID PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    boundary   geometry(MultiPolygon, 4326)
);
