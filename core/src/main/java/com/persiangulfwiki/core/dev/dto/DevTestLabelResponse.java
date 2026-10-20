package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.label.dto.LabelReferentKind;

import java.util.UUID;

public record DevTestLabelResponse(UUID labelId, LabelReferentKind referentKind, UUID referentId, String language,
        String value) {
}
