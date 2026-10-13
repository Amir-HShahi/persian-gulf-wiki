package com.persiangulfwiki.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

public record PublicProfileResponse(
        String username,
        String fullName,
        String avatarUrl,
        @Schema(nullable = true) @Nullable String bio,
        @Schema(nullable = true) @Nullable String affiliation,
        List<String> links,
        Instant joinedAt,
        List<String> roles,
        @Schema(description = "Approved revisions + articles created + published media + translations started, "
                + "counted only on articles that are public.")
        long contributionCount) {
}
