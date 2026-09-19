package com.persiangulfwiki.core.article.entity;

// The ERD's full typed-referent list for an article. Mirrored by the CHECK constraint in V16,
// same maintenance contract as SubjectKind/V15: a new value here means a migration alongside
// it.
//
// STRAIT is real in the ERD but has no matching SubjectKind (see
// com.persiangulfwiki.core.subject.entity.SubjectKind) -- Phase 1 never created a straits
// subtype table. An article can only derive its entityType from subjectId != null by reading
// the bound Subject's kind, so STRAIT is unreachable through that path until a STRAIT
// SubjectKind (and its subtype table) exists. It remains selectable directly for a
// subject-less (GENERIC-style) article in the meantime, same as any other value.
//
// GENERIC is the only value a client may ever supply explicitly: every other value is
// derived from subjectId and rejected if the client sends it. See ArticleService.create.
public enum EntityType {
    ISLAND, PORT, OIL_FIELD, SPECIES, STRAIT, GENERIC
}
