package com.persiangulfwiki.core.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        @Schema(nullable = true, description = "Missing for a Google signup that hasn't finished registering.")
        @Nullable String username,
        @Schema(description = "The name to show: the full name, else the username, else the part of the email "
                + "before the @.")
        String displayName,
        String email,
        @Schema(nullable = true, description = "Missing when the user has no profile picture.")
        @Nullable String avatarUrl,
        @Schema(description = "False for a suspended or banned account.")
        boolean enabled,
        boolean emailVerified,
        List<RoleAssignment> roles,
        @Schema(description = "Approved revisions + articles created + published media + translations started, "
                + "counted only on articles that are public. The same number the user's public profile shows.")
        long contributionCount,
        @Schema(description = "When the user signed up.")
        Instant createdAt) {
}
