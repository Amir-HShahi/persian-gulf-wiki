package com.persiangulfwiki.core.subject.entity;

// The four typed subtypes the schema defines, each with its own detail table sharing
// subjects.id (see Island/Port/OilField/Species and V15). Adding a value here without the
// matching table, entity and SubjectService branch produces a subject that can be created
// but never carries any of its own data — so the CHECK constraint in V15 deliberately
// mirrors this list, and a new kind means a migration alongside the enum change.
public enum SubjectKind {
    ISLAND, PORT, OIL_FIELD, SPECIES
}
