package com.persiangulfwiki.core.admin.service;

import com.persiangulfwiki.core.admin.dto.AdminUserResponse;
import com.persiangulfwiki.core.admin.dto.RoleAssignment;
import com.persiangulfwiki.core.admin.dto.RoleGrantRequest;
import com.persiangulfwiki.core.admin.dto.UserStatusAction;
import com.persiangulfwiki.core.admin.dto.UserStatusRequest;
import com.persiangulfwiki.core.admin.exception.InvalidPagingParametersException;
import com.persiangulfwiki.core.admin.exception.LastAdminException;
import com.persiangulfwiki.core.admin.exception.RoleAlreadyGrantedException;
import com.persiangulfwiki.core.admin.exception.RoleNotGrantedException;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;

    public List<AdminUserResponse> listUsers(int page, int size) {
        if (page < 0 || size < 1) {
            throw new InvalidPagingParametersException("page must be >= 0 and size must be >= 1");
        }
        return userRepository.findAll(PageRequest.of(page, size)).stream()
                .map(this::toAdminUserResponse)
                .toList();
    }

    @Transactional
    public void grantOrRevokeRole(UUID actingAdminId, UUID targetUserId, RoleGrantRequest request) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserNotFoundException("user not found: " + targetUserId));

        String detail = "role=" + request.role() + ", entityType=" + request.entityType();

        switch (request.action()) {
            case GRANT -> {
                if (userRoleRepository.findByUserIdAndRoleAndEntityType(targetUserId, request.role(), request.entityType())
                        .isPresent()) {
                    throw new RoleAlreadyGrantedException();
                }

                userRoleRepository.save(UserRole.builder()
                        .user(targetUser)
                        .role(request.role())
                        .entityType(request.entityType())
                        .build());

                auditLogService.record(actingAdminId, "ROLE_GRANT", targetUserId, detail);
                logIfAdminRole(request.role(), "ROLE_GRANT", actingAdminId, targetUserId, detail);
            }
            case REVOKE -> {
                UserRole existing = userRoleRepository
                        .findByUserIdAndRoleAndEntityType(targetUserId, request.role(), request.entityType())
                        .orElseThrow(RoleNotGrantedException::new);

                // The highest-trust action this endpoint can take: revoking the system's
                // last ADMIN would leave no one able to grant the next one back, so it's
                // blocked outright rather than merely logged loudly.
                if (request.role() == Role.ADMIN && userRoleRepository.countByRole(Role.ADMIN) <= 1) {
                    throw new LastAdminException();
                }

                userRoleRepository.delete(existing);

                auditLogService.record(actingAdminId, "ROLE_REVOKE", targetUserId, detail);
                logIfAdminRole(request.role(), "ROLE_REVOKE", actingAdminId, targetUserId, detail);
            }
        }
    }

    @Transactional
    public void updateStatus(UUID actingAdminId, UUID targetUserId, UserStatusRequest request) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserNotFoundException("user not found: " + targetUserId));

        // SUSPEND and BAN are administratively distinct but this codebase's `users` table
        // only has a boolean `enabled` column today (no status/reason column) — both map to
        // enabled=false. The requested action (which of the two was meant) is preserved only
        // in the audit log, not as separate persisted state. See AUTH_PLAN.md Section 2.
        boolean enabled = request.action() == UserStatusAction.REINSTATE;
        targetUser.setEnabled(enabled);
        userRepository.save(targetUser);

        auditLogService.record(actingAdminId, "USER_STATUS_" + request.action(), targetUserId, "enabled=" + enabled);
    }

    private void logIfAdminRole(Role role, String action, UUID actingAdminId, UUID targetUserId, String detail) {
        // Every ADMIN grant/revoke is the highest-trust action in the system, so it also
        // gets a WARN-level log line in addition to the normal audit record, per
        // AUTH_PLAN.md's "especially loud" instruction — the audit table alone doesn't
        // surface in ops logs/alerts the way a WARN does.
        if (role == Role.ADMIN) {
            log.warn("ADMIN role {} by actingAdminId={} on targetUserId={} ({})", action, actingAdminId, targetUserId, detail);
        }
    }

    private AdminUserResponse toAdminUserResponse(User user) {
        List<RoleAssignment> roles = userRoleRepository.findByUserId(user.getId()).stream()
                .map(userRole -> new RoleAssignment(userRole.getRole(), userRole.getEntityType()))
                .toList();
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.isEnabled(),
                user.isEmailVerified(),
                roles,
                user.getCreatedAt());
    }
}
