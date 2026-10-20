package com.persiangulfwiki.core.label.dto;

import org.jspecify.annotations.Nullable;

// Every language's label for one referent, for admin responses. Null where none exists.
public record LabelValuesResponse(@Nullable String fa, @Nullable String en, @Nullable String ar) {
}
