package com.persiangulfwiki.core.label.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// What a label names. KIND labels a SubjectKind, which has no row of its own; its "id" is the
// enum name.
@Schema(description = LabelReferentKind.VALUES_DOC)
public enum LabelReferentKind {
    GROUP, ATTRIBUTE, OPTION, SUBJECT, KIND, TOPIC;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `GROUP`: a field group.
            - `ATTRIBUTE`: a field definition.
            - `OPTION`: one choice value of an ENUM field.
            - `SUBJECT`: a subject's name. Read-only through labels: a subject's names change only through a moderated \
            subject revision.
            - `KIND`: a subject kind; its id is the kind itself, e.g. `ISLAND`.
            - `TOPIC`: a topic.""";
}
