package com.persiangulfwiki.core.subject.dto;

// What a duplicate lookup matched on: one of the subject's names, one of its aliases, or its
// scientific name (a language-neutral fact of species).
public enum SubjectLookupMatch {
    NAME, ALIAS, SCIENTIFIC_NAME
}
