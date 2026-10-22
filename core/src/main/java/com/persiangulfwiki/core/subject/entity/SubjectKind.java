package com.persiangulfwiki.core.subject.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// The typed subtypes the schema defines, each with its own detail table sharing subjects.id
// (see Island/Port/OilField/Species/Strait/FishingGround/Province, V15 and V21). Adding a value
// here without the matching table, entity and SubjectService branch produces a subject that
// can be created but never carries any of its own data — so the CHECK constraint on
// subjects.kind deliberately mirrors this list, and a new kind means a migration alongside the
// enum change. The same migration must widen every other CHECK that mirrors this list too: the
// expertise CHECKs on user_roles and expert_reviewer_applications (V20), and ref_kind,
// attribute_definition_kinds.kind and labels.kind (V22) — and should seed the kind's fa/en/ar
// labels, or the kind facet shows it unlabeled.
@Schema(description = SubjectKind.VALUES_DOC)
public enum SubjectKind {
    ISLAND, PORT, OIL_FIELD, SPECIES, STRAIT, FISHING_GROUND, PROVINCE;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `ISLAND`: an island.
            - `PORT`: a port or harbour.
            - `OIL_FIELD`: an oil or gas field.
            - `SPECIES`: a living species, such as a fish, coral or bird.
            - `STRAIT`: a strait.
            - `FISHING_GROUND`: a fishing ground.
            - `PROVINCE`: a province.""";
}
