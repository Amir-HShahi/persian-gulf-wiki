package com.persiangulfwiki.core.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String username,
        String email,
        boolean enabled,
        boolean emailVerified,
        List<RoleAssignment> roles,
        Instant createdAt) {
}
