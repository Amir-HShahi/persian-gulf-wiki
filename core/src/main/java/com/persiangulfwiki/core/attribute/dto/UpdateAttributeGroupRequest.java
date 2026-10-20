package com.persiangulfwiki.core.attribute.dto;

import org.jspecify.annotations.Nullable;

// Replaces the group's mutable fields in place, not a merge-patch — same convention as
// UpdateRevisionRequest. `sort` is the only one; omitting it sets 0.
public record UpdateAttributeGroupRequest(@Nullable Integer sort) {
}
