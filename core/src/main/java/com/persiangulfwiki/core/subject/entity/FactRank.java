package com.persiangulfwiki.core.subject.entity;

// Which of a field's values readers see. Only PREFERRED values show in the infobox and count in
// facets; NORMAL keeps an alternative on record, e.g. an older census figure next to the current
// one. Mirrored by V24's ck_subject_facts_rank.
public enum FactRank {
    PREFERRED, NORMAL
}
