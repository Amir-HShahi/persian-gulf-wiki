package com.persiangulfwiki.core.user.repository;

import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    List<UserRole> findByUserId(UUID userId);

    boolean existsByRole(Role role);

    // Derived query method: a null entityType argument is translated to an "IS NULL" check
    // by Spring Data JPA, not a literal "= null" comparison, so this also correctly matches
    // global roles (MODERATOR, ADMIN) whose entity_type column is null.
    Optional<UserRole> findByUserIdAndRoleAndEntityType(UUID userId, Role role, String entityType);

    long countByRole(Role role);
}
