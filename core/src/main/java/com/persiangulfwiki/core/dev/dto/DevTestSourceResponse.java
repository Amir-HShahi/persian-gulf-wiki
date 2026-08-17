package com.persiangulfwiki.core.dev.dto;

import java.util.UUID;

public record DevTestSourceResponse(UUID sourceId, String title, UUID createdByUserId) {
}
