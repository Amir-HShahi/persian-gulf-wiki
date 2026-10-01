package com.persiangulfwiki.core.label.dto;

import java.time.Instant;

// `referentId` is a UUID, or the kind's name for a KIND label.
public record LabelResponse(
        LabelReferentKind referentKind,
        String referentId,
        String language,
        String value,
        Instant updatedAt) {
}
