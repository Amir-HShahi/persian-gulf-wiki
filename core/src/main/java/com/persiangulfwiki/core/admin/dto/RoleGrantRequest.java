package com.persiangulfwiki.core.admin.dto;

import com.persiangulfwiki.core.user.entity.Role;
import jakarta.validation.constraints.NotNull;

// entityType is nullable by design: required to be meaningful only for EXPERT_REVIEWER
// grants/revokes, null for global roles (MODERATOR, ADMIN) — matches UserRole.entityType.
public record RoleGrantRequest(
        @NotNull(message = "{validation.role.required}") Role role,
        String entityType,
        @NotNull(message = "{validation.action.required}") GrantAction action) {
}
