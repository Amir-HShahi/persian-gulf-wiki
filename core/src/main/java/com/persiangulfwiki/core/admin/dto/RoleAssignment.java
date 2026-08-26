package com.persiangulfwiki.core.admin.dto;

import com.persiangulfwiki.core.user.entity.Role;

// entityType is null for global roles (MODERATOR, ADMIN) and set for entity-scoped roles
// (EXPERT_REVIEWER), mirroring UserRole's own nullable entity_type column.
public record RoleAssignment(Role role, String entityType) {
}
