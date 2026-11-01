package com.persiangulfwiki.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        @Schema(nullable = true) @Nullable String username,
        String email,
        List<String> roles,
        @Schema(nullable = true) @Nullable String fullName,
        @Schema(description = "Public URL of the avatar.", nullable = true) @Nullable String avatarUrl,
        @Schema(nullable = true) @Nullable String bio,
        @Schema(nullable = true) @Nullable String affiliation,
        List<String> links,
        Instant joinedAt,
        @Schema(description = "True once both `fullName` and an avatar are set. Until then the public profile "
                + "(GET /api/profiles/{username}) is 404.")
        boolean isProfileComplete) {
}
