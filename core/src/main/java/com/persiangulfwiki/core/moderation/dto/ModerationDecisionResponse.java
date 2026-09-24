package com.persiangulfwiki.core.moderation.dto;

import com.persiangulfwiki.core.moderation.entity.Decision;

import java.time.Instant;
import java.util.UUID;

public record ModerationDecisionResponse(
        UUID id, UUID taskId, UUID moderatorId, Decision decision, String reason, Instant createdAt) {
}
