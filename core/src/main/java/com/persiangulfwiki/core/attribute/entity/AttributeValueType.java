package com.persiangulfwiki.core.attribute.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// How a field's facts are stored. PLAIN_TEXT is language-neutral (a scientific name);
// LOCALIZED_TEXT holds one value per language. Mirrored by V22's value_type CHECK.
@Schema(description = AttributeValueType.VALUES_DOC)
public enum AttributeValueType {
    NUMBER, ENUM, SUBJECT_REF, DATE, BOOL, PLAIN_TEXT, LOCALIZED_TEXT;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `NUMBER`: a decimal number in the field's `unit`, within its `minValue`/`maxValue` when set. Every value \
            cites a source.
            - `ENUM`: one of the field's choice values, sent and returned as its option code.
            - `SUBJECT_REF`: a link to another subject, which must be of the field's `refKind`.
            - `DATE`: a calendar date, YYYY-MM-DD.
            - `BOOL`: true or false.
            - `PLAIN_TEXT`: one text that is the same in every language, such as a scientific name. Can't be used as a \
            filter.
            - `LOCALIZED_TEXT`: one text per language (Farsi required, English and Arabic optional). Can't be used as \
            a filter.""";

    // Free text can't be counted into checkbox facets; mirrors ck_attribute_definitions_filterable_type.
    public boolean canBeFiltered() {
        return this != PLAIN_TEXT && this != LOCALIZED_TEXT;
    }
}
