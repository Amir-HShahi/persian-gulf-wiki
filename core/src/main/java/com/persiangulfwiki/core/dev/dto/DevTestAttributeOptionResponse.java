package com.persiangulfwiki.core.dev.dto;

import java.util.UUID;

public record DevTestAttributeOptionResponse(UUID optionId, UUID definitionId, String code, boolean isDeprecated) {
}
