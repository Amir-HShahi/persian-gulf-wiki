package com.persiangulfwiki.core.admin.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.user.entity.Role;

// expertise is null for global roles (MODERATOR, ADMIN) and set for expertise-scoped roles
// (EXPERT_REVIEWER), mirroring UserRole's own nullable expertise column.
public record RoleAssignment(Role role, SubjectKind expertise) {
}
