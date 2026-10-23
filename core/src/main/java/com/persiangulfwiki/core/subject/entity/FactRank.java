package com.persiangulfwiki.core.subject.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// Which of a field's values readers see. Only PREFERRED values show in the infobox and count in
// facets; NORMAL keeps an alternative on record, e.g. an older census figure next to the current
// one. Mirrored by V24's ck_subject_facts_rank.
@Schema(description = FactRank.VALUES_DOC)
public enum FactRank {
    PREFERRED, NORMAL;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `PREFERRED`: the value readers see: shown in the infobox and counted in filters.
            - `NORMAL`: kept on record as an alternative, such as an older census figure next to the current one; not \
            shown or counted.""";
}
