package com.persiangulfwiki.core.attribute.dto;

import org.jspecify.annotations.Nullable;

// Replaces the option's mutable fields in place, not a merge-patch — same convention as
// UpdateRevisionRequest. Omitted fields are 0/false.
public record UpdateAttributeOptionRequest(@Nullable Integer sort, @Nullable Boolean isDeprecated) {
}
