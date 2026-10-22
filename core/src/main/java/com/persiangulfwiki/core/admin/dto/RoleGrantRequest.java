package com.persiangulfwiki.core.admin.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.user.entity.Role;
import jakarta.validation.constraints.NotNull;

// expertise is nullable by design: required to be meaningful only for EXPERT_REVIEWER
// grants/revokes, null for global roles (MODERATOR, ADMIN) — matches UserRole.expertise.
public record RoleGrantRequest(
        @NotNull(message = "{validation.role.required}") Role role,
        SubjectKind expertise,
        @NotNull(message = "{validation.action.required}") GrantAction action) {
}
