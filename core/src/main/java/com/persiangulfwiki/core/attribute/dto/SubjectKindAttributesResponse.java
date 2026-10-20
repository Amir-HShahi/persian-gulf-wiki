package com.persiangulfwiki.core.attribute.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.jspecify.annotations.Nullable;

import java.util.List;

// The field vocabulary of one kind in the reader's language: what the edit form and the filter
// panel are built from. A label is null only when no Farsi label exists either.
public record SubjectKindAttributesResponse(
        SubjectKind kind,
        @Nullable LocalizedLabelResponse label,
        List<KindAttributeGroupResponse> groups) {
}
