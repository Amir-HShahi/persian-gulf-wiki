package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// What a duplicate lookup matched on: one of the subject's names, one of its aliases, or its
// scientific name (a language-neutral fact of species).
@Schema(description = SubjectLookupMatch.VALUES_DOC)
public enum SubjectLookupMatch {
    NAME, ALIAS, SCIENTIFIC_NAME;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `NAME`: one of the subject's names.
            - `ALIAS`: one of the subject's aliases.
            - `SCIENTIFIC_NAME`: the scientific name of a species.""";
}
