package com.persiangulfwiki.core.subject.dto;

import org.jspecify.annotations.Nullable;

// One text per language: a subject's names, or a LOCALIZED_TEXT fact value. Farsi is required
// in both uses; checked in SubjectPayloadValidationService with the rest of the payload, so the
// failure carries its full field path.
public record LocalizedTextRequest(@Nullable String fa, @Nullable String en, @Nullable String ar) {
}
