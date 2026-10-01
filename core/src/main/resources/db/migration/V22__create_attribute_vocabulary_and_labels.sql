-- The field vocabulary for subject infoboxes and facet filters. Facts are stored as codes, not
-- prose (a later migration adds subject_facts), and every human-readable name -- a group's,
-- a field's, an option's, a subject's, a kind's -- lives in `labels`, looked up at read time
-- in the reader's language. Admin-only to change; there is no moderation queue for any of it.
--
-- Codes are immutable once created. `key` and `code` below are what facts, URLs
-- (attr.{key}=CODE) and frontend lookups refer to, so renaming one would silently orphan all
-- of them. The API offers no rename or delete for a code, only is_deprecated, and the
-- displayed text changes through `labels` instead.
--
-- The format CHECKs on key and code are what make `attr.{key}=CODE1,CODE2` parseable: a code
-- holding a comma or a key holding a dot would split wrongly in the facet listing's query
-- string.

CREATE TABLE attribute_groups (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key        VARCHAR(60) NOT NULL,
    sort       INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as subjects.dev_marker (V15). Without it an E2E suite has no disposable group
    -- to exercise the admin PATCH on, and would have to mutate a seeded one.
    dev_marker VARCHAR(40),
    CONSTRAINT uq_attribute_groups_key UNIQUE (key),
    CONSTRAINT ck_attribute_groups_key CHECK (key ~ '^[a-z][a-z0-9_]*$')
);

CREATE INDEX idx_attribute_groups_dev_marker ON attribute_groups (dev_marker) WHERE dev_marker IS NOT NULL;

CREATE TABLE attribute_definitions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key           VARCHAR(60) NOT NULL,
    -- Mirrors AttributeValueType. PLAIN_TEXT is language-neutral (a scientific name);
    -- LOCALIZED_TEXT carries one value per language.
    value_type    VARCHAR(20) NOT NULL,
    -- A unit code the frontend localizes (e.g. "cm", "km2"), never display text.
    unit          VARCHAR(20),
    -- RESTRICT (the default): a group is never deleted out from under its fields. The dev
    -- sweeper deletes minted definitions before minted groups for this reason.
    group_id      UUID NOT NULL REFERENCES attribute_groups (id),
    sort          INTEGER NOT NULL DEFAULT 0,
    is_multi      BOOLEAN NOT NULL,
    is_required   BOOLEAN NOT NULL,
    is_infobox    BOOLEAN NOT NULL,
    is_filterable BOOLEAN NOT NULL,
    -- The kind a SUBJECT_REF value must point at (e.g. province -> PROVINCE).
    ref_kind      VARCHAR(30),
    min_value     NUMERIC,
    max_value     NUMERIC,
    is_deprecated BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    dev_marker    VARCHAR(40),
    CONSTRAINT uq_attribute_definitions_key UNIQUE (key),
    CONSTRAINT ck_attribute_definitions_key CHECK (key ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT ck_attribute_definitions_value_type
        CHECK (value_type IN ('NUMBER', 'ENUM', 'SUBJECT_REF', 'DATE', 'BOOL', 'PLAIN_TEXT', 'LOCALIZED_TEXT')),
    -- Mirrors SubjectKind, same maintenance contract as subjects.kind's CHECK.
    CONSTRAINT ck_attribute_definitions_ref_kind
        CHECK (ref_kind IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES', 'STRAIT', 'FISHING_GROUND', 'PROVINCE')),
    CONSTRAINT ck_attribute_definitions_ref_kind_iff_subject_ref
        CHECK ((ref_kind IS NOT NULL) = (value_type = 'SUBJECT_REF')),
    CONSTRAINT ck_attribute_definitions_unit_number_only
        CHECK (unit IS NULL OR value_type = 'NUMBER'),
    -- Same reasoning as the unit rule: a bound on a non-number is meaningless, and an
    -- inverted range would reject every value.
    CONSTRAINT ck_attribute_definitions_bounds_number_only
        CHECK ((min_value IS NULL AND max_value IS NULL) OR value_type = 'NUMBER'),
    CONSTRAINT ck_attribute_definitions_bounds_ordered
        CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value),
    -- Free text can't be a checkbox facet. NUMBER is allowed for future range filters.
    CONSTRAINT ck_attribute_definitions_filterable_type
        CHECK (NOT is_filterable OR value_type IN ('ENUM', 'SUBJECT_REF', 'NUMBER', 'BOOL', 'DATE'))
);

CREATE INDEX idx_attribute_definitions_group ON attribute_definitions (group_id);
CREATE INDEX idx_attribute_definitions_dev_marker
    ON attribute_definitions (dev_marker) WHERE dev_marker IS NOT NULL;

-- Many kinds per field: the places page lists islands, ports, fishing grounds... together and
-- shares fields such as `province` across them, so a field cannot carry a single kind column.
CREATE TABLE attribute_definition_kinds (
    attribute_id UUID NOT NULL REFERENCES attribute_definitions (id) ON DELETE CASCADE,
    kind         VARCHAR(30) NOT NULL,
    PRIMARY KEY (attribute_id, kind),
    CONSTRAINT ck_attribute_definition_kinds_kind
        CHECK (kind IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES', 'STRAIT', 'FISHING_GROUND', 'PROVINCE'))
);

-- The public "fields of kind X" read starts from the kind, which the PK can't serve.
CREATE INDEX idx_attribute_definition_kinds_kind ON attribute_definition_kinds (kind);

-- The values of an ENUM field. Only ENUM definitions have options; that cross-table rule is
-- enforced in AttributeDefinitionService, since a CHECK cannot see another table.
--
-- No dev_marker of its own: a minted option always hangs off a minted definition and goes
-- with it through the CASCADE.
CREATE TABLE attribute_options (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attribute_id  UUID NOT NULL REFERENCES attribute_definitions (id) ON DELETE CASCADE,
    code          VARCHAR(60) NOT NULL,
    sort          INTEGER NOT NULL DEFAULT 0,
    is_deprecated BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT uq_attribute_options_attribute_code UNIQUE (attribute_id, code),
    CONSTRAINT ck_attribute_options_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_]*$')
);

-- One row per (referent, language). Farsi is canonical; English and Arabic are optional and the
-- read side falls back to Farsi, marking the fallback.
--
-- One nullable FK column per referent plus an exactly-one CHECK, not a polymorphic
-- (referent_kind, referent_id) pair -- the V18 moderation_tasks reasoning: a real FK with
-- ON DELETE CASCADE removes a subject's or a minted definition's labels together with it,
-- including through the dev sweepers' bulk deletes, where a polymorphic pair would leave
-- orphans that every delete path would have to remember to clean up.
--
-- `kind` is the one referent with no row of its own: SubjectKind is an enum, so the column
-- holds its name. It labels the kind facet on listing pages.
CREATE TABLE labels (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id     UUID REFERENCES attribute_groups (id) ON DELETE CASCADE,
    attribute_id UUID REFERENCES attribute_definitions (id) ON DELETE CASCADE,
    option_id    UUID REFERENCES attribute_options (id) ON DELETE CASCADE,
    subject_id   UUID REFERENCES subjects (id) ON DELETE CASCADE,
    kind         VARCHAR(30),
    -- Mirrors config/SupportedLocales.
    language     VARCHAR(2) NOT NULL,
    value        VARCHAR(200) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_labels_exactly_one_referent
        CHECK (num_nonnulls(group_id, attribute_id, option_id, subject_id, kind) = 1),
    CONSTRAINT ck_labels_kind
        CHECK (kind IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES', 'STRAIT', 'FISHING_GROUND', 'PROVINCE')),
    CONSTRAINT ck_labels_language CHECK (language IN ('fa', 'en', 'ar')),
    CONSTRAINT ck_labels_value_not_blank CHECK (btrim(value) <> ''),
    -- Postgres UNIQUE ignores NULLs, so each constraint only compares rows of its own
    -- referent, and each doubles as the lookup index for that referent.
    CONSTRAINT uq_labels_group_language UNIQUE (group_id, language),
    CONSTRAINT uq_labels_attribute_language UNIQUE (attribute_id, language),
    CONSTRAINT uq_labels_option_language UNIQUE (option_id, language),
    CONSTRAINT uq_labels_subject_language UNIQUE (subject_id, language),
    CONSTRAINT uq_labels_kind_language UNIQUE (kind, language)
);
