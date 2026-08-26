package com.persiangulfwiki.core.admin;

import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

// Seeds the first ADMIN account from env-var-driven credentials, since an admin can't grant
// the first admin — something has to exist before the role-grant chain starts. No-op unless
// ADMIN_BOOTSTRAP_EMAIL, ADMIN_BOOTSTRAP_USERNAME, and ADMIN_BOOTSTRAP_PASSWORD are all set.
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @Value("${app.admin.bootstrap-email}")
    private final String bootstrapEmail;

    @Value("${app.admin.bootstrap-username}")
    private final String bootstrapUsername;

    @Value("${app.admin.bootstrap-password}")
    private final String bootstrapPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(bootstrapEmail) || !StringUtils.hasText(bootstrapUsername)
                || !StringUtils.hasText(bootstrapPassword)) {
            log.debug("admin bootstrap not configured, skipping");
            return;
        }

        if (userRoleRepository.existsByRole(Role.ADMIN)) {
            log.info("an admin already exists, skipping admin bootstrap");
            return;
        }

        User admin = userRepository.findByEmail(bootstrapEmail)
                // Existing user, no ADMIN role yet: grant it rather than failing or creating
                // a duplicate-email user, which would violate the unique email constraint.
                .orElseGet(() -> userRepository.save(User.builder()
                        .username(bootstrapUsername)
                        .email(bootstrapEmail)
                        .passwordHash(passwordEncoder.encode(bootstrapPassword))
                        .emailVerified(true)
                        .enabled(true)
                        .build()));

        userRoleRepository.save(UserRole.builder()
                .user(admin)
                .role(Role.ADMIN)
                .build());

        auditLogService.record(null, "ADMIN_BOOTSTRAP", admin.getId(),
                "first admin account bootstrapped from ADMIN_BOOTSTRAP_EMAIL env var");
        log.warn("ADMIN_BOOTSTRAP: first admin account bootstrapped (userId={})", admin.getId());
    }
}
