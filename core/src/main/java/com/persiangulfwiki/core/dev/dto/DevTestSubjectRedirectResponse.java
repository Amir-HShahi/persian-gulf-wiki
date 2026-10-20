package com.persiangulfwiki.core.dev.dto;

import java.time.Instant;
import java.util.UUID;

public record DevTestSubjectRedirectResponse(UUID sourceId, UUID targetId, Instant mergedAt) {
}
