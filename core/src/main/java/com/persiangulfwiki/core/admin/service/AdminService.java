package com.persiangulfwiki.core.admin.service;

import com.persiangulfwiki.core.admin.dto.AdminUserCounts;
import com.persiangulfwiki.core.admin.dto.AdminUserListResponse;
import com.persiangulfwiki.core.admin.dto.AdminUserResponse;
import com.persiangulfwiki.core.admin.dto.RoleAssignment;
import com.persiangulfwiki.core.admin.dto.RoleGrantRequest;
import com.persiangulfwiki.core.admin.dto.UserStatusAction;
import com.persiangulfwiki.core.admin.dto.UserRoleFilter;
import com.persiangulfwiki.core.admin.dto.UserStatusRequest;
import com.persiangulfwiki.core.admin.exception.InvalidPagingParametersException;
import com.persiangulfwiki.core.admin.exception.InvalidUserRoleFilterException;
import com.persiangulfwiki.core.admin.exception.LastAdminException;
import com.persiangulfwiki.core.admin.exception.RoleAlreadyGrantedException;
import com.persiangulfwiki.core.admin.exception.RoleNotGrantedException;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;
import com.persiangulfwiki.core.user.repository.AdminUserCountsRow;
import com.persiangulfwiki.core.user.repository.UserContributionCountRow;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;
import com.persiangulfwiki.core.user.service.AvatarService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;
    private final AvatarService avatarService;

    // roleFilters OR together; enabled, when set, narrows on top. Counts ignore both.
    @Transactional(readOnly = true)
    public AdminUserListResponse listUsers(int page, int size, List<String> roleFilters, @Nullable Boolean enabled,
            @Nullable String query) {
        if (page < 0 || size < 1) {
            throw new InvalidPagingParametersException("page must be >= 0 and size must be >= 1");
        }
        Set<UserRoleFilter> roles = roleFilters.stream().map(AdminService::parseRoleFilter)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(UserRoleFilter.class)));
        boolean anyRole = !roles.isEmpty();
        boolean admin = roles.contains(UserRoleFilter.ADMIN);
        boolean moderator = roles.contains(UserRoleFilter.MODERATOR);
        boolean expertReviewer = roles.contains(UserRoleFilter.EXPERT_REVIEWER);
        boolean contributorOnly = roles.contains(UserRoleFilter.CONTRIBUTOR);
        boolean filterEnabled = enabled != null;
        boolean enabledValue = enabled == null || enabled;
        String search = query == null ? "" : query.strip();
        boolean filterQuery = !search.isEmpty();

        List<User> users = userRepository.findForAdmin(filterEnabled, enabledValue, anyRole, admin, moderator,
                expertReviewer, contributorOnly, filterQuery, search, size, (long) page * size);
        long total = userRepository.countForAdmin(filterEnabled, enabledValue, anyRole, admin, moderator,
                expertReviewer, contributorOnly, filterQuery, search);

        List<UUID> ids = users.stream().map(User::getId).toList();
        Map<UUID, List<RoleAssignment>> rolesByUser = ids.isEmpty() ? Map.of()
                : userRoleRepository.findByUserIdIn(ids).stream().collect(Collectors.groupingBy(
                        userRole -> userRole.getUser().getId(),
                        Collectors.mapping(userRole -> new RoleAssignment(userRole.getRole(), userRole.getExpertise()),
                                Collectors.toList())));
        Map<UUID, Long> contributionsByUser = ids.isEmpty() ? Map.of()
                : userRepository.countContributionsByUser(ids).stream().collect(Collectors.toMap(
                        UserContributionCountRow::getUserId, UserContributionCountRow::getContributionCount));

        List<AdminUserResponse> items = users.stream()
                .map(user -> toAdminUserResponse(user, rolesByUser.getOrDefault(user.getId(), List.of()),
                        contributionsByUser.getOrDefault(user.getId(), 0L)))
                .toList();
        AdminUserCountsRow counts = userRepository.countUsersForAdmin();
        return new AdminUserListResponse(items, total, new AdminUserCounts(counts.getTotal(), counts.getBlocked(),
                counts.getAdmin(), counts.getModerator(), counts.getExpertReviewer(), counts.getContributor()));
    }

    private static UserRoleFilter parseRoleFilter(String value) {
        try {
            return UserRoleFilter.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidUserRoleFilterException("invalid role filter: " + value);
        }
    }

    @Transactional
    public void grantOrRevokeRole(UUID actingAdminId, UUID targetUserId, RoleGrantRequest request) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserNotFoundException("user not found: " + targetUserId));

        String detail = "role=" + request.role() + ", expertise=" + request.expertise();

        switch (request.action()) {
            case GRANT -> {
                if (userRoleRepository.findByUserIdAndRoleAndExpertise(targetUserId, request.role(), request.expertise())
                        .isPresent()) {
                    throw new RoleAlreadyGrantedException();
                }

                userRoleRepository.save(UserRole.builder()
                        .user(targetUser)
                        .role(request.role())
                        .expertise(request.expertise())
                        .build());

                auditLogService.record(actingAdminId, "ROLE_GRANT", targetUserId, detail);
                logIfAdminRole(request.role(), "ROLE_GRANT", actingAdminId, targetUserId, detail);
            }
            case REVOKE -> {
                UserRole existing = userRoleRepository
                        .findByUserIdAndRoleAndExpertise(targetUserId, request.role(), request.expertise())
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

    private AdminUserResponse toAdminUserResponse(User user, List<RoleAssignment> roles, long contributionCount) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                displayName(user),
                user.getEmail(),
                avatarService.publicUrl(user.getAvatarKey()).orElse(null),
                user.isEnabled(),
                user.isEmailVerified(),
                List.copyOf(roles),
                contributionCount,
                user.getCreatedAt());
    }

    private static String displayName(User user) {
        if (user.getFullName() != null) {
            return user.getFullName();
        }
        if (user.getUsername() != null) {
            return user.getUsername();
        }
        return user.getEmail().substring(0, user.getEmail().indexOf('@'));
    }
}
