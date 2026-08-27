package com.persiangulfwiki.core.expertreviewer.dto;

import jakarta.validation.constraints.NotNull;

public record ReviewDecisionRequest(
        @NotNull(message = "{validation.decision.required}") ReviewDecision decision) {
}
