package com.persiangulfwiki.core.attribute.entity;

// How a field's facts are stored. PLAIN_TEXT is language-neutral (a scientific name);
// LOCALIZED_TEXT holds one value per language. Mirrored by V22's value_type CHECK.
public enum AttributeValueType {
    NUMBER, ENUM, SUBJECT_REF, DATE, BOOL, PLAIN_TEXT, LOCALIZED_TEXT;

    // Free text can't be counted into checkbox facets; mirrors ck_attribute_definitions_filterable_type.
    public boolean canBeFiltered() {
        return this != PLAIN_TEXT && this != LOCALIZED_TEXT;
    }
}
