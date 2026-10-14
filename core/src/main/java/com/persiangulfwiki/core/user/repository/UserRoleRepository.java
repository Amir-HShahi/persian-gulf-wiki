package com.persiangulfwiki.core.user.repository;

import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    List<UserRole> findByUserId(UUID userId);

    List<UserRole> findByUserIdIn(Collection<UUID> userIds);

    boolean existsByRole(Role role);

    // Derived query method: a null expertise argument is translated to an "IS NULL" check
    // by Spring Data JPA, not a literal "= null" comparison, so this also correctly matches
    // global roles (MODERATOR, ADMIN) whose expertise column is null.
    Optional<UserRole> findByUserIdAndRoleAndExpertise(UUID userId, Role role, SubjectKind expertise);

    long countByRole(Role role);
}
