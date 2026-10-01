package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.attribute.entity.AttributeValueType;

import java.util.UUID;

public record DevTestAttributeDefinitionResponse(UUID definitionId, String key, UUID groupId,
        AttributeValueType valueType) {
}
