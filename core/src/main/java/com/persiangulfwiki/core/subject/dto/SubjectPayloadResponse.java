package com.persiangulfwiki.core.subject.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

// The stored payload, field for field the shape SubjectPayloadRequest accepts, so a client can
// send back what it read with only its changes applied.
public record SubjectPayloadResponse(
        @Nullable LocalizedTextResponse names,
        @Nullable SubjectAliasesResponse aliases,
        @Nullable List<SubjectFactResponse> facts,
        @Nullable String geometry,
        @Nullable UUID mainImageMediaId) {
}
