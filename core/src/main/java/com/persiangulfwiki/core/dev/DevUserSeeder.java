package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Seeds a fixed cast of local-development accounts covering the account states the frontend
// has to render. Two of them — unverified and disabled — cannot be produced by going through
// the signup flow at all, which is the main reason this exists.
//
// The gate is @Profile("dev"), not a config flag: these credentials are public and committed,
// so the bean must not merely skip itself outside dev, it must not exist. Contrast
// AdminBootstrapRunner, which is correctly env-var-gated — bootstrapping the first admin is a
// legitimate production operation with an operator-chosen password. Gate on the environment
// when the data is unsafe anywhere but dev; gate on config when the operation is legitimate
// everywhere. (A seed.sql under db/migration would be the wrong tool entirely — Flyway applies
// every migration in every environment, so it would run in production with no guard at all.)
@Slf4j
@Component
@Profile("dev")
// AdminBootstrapRunner skips itself once any ADMIN exists, so if this seeder won the startup
// race it would silently suppress a configured ADMIN_BOOTSTRAP_* account. Explicit ordering
// (see @Order(1) there) makes the outcome deterministic instead of merely usually-fine.
@Order(2)
@RequiredArgsConstructor
public class DevUserSeeder implements ApplicationRunner {

    // Shared by every seeded account, and documented in docs/DEV-SEED.md. Satisfies
    // PasswordConstraintValidator's rules so these accounts can also be used to exercise
    // password-change flows that re-validate the current password.
    private static final String DEV_PASSWORD = "Dev-Password1!";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("contributor@dev.local", "dev_contributor", true, true, Role.CONTRIBUTOR);
        seed("unverified@dev.local", "dev_unverified", false, true, Role.CONTRIBUTOR);
        seed("moderator@dev.local", "dev_moderator", true, true, Role.CONTRIBUTOR, Role.MODERATOR);
        // EXPERT_REVIEWER is orthogonal to the ADMIN/MODERATOR ladder (see RoleHierarchyConfig),
        // so a reviewer also holds CONTRIBUTOR — that's what an approved applicant looks like.
        seed("reviewer@dev.local", "dev_reviewer", true, true, Role.CONTRIBUTOR, Role.EXPERT_REVIEWER);
        seed("admin@dev.local", "dev_admin", true, true, Role.CONTRIBUTOR, Role.ADMIN);
        seed("disabled@dev.local", "dev_disabled", true, false, Role.CONTRIBUTOR);

        log.info("dev user seeding complete — accounts share the password documented in docs/DEV-SEED.md");
    }

    // Find-or-create, not insert: the compose Postgres volume survives restarts, so this runs
    // against an already-seeded database on every boot but the second boot must be a no-op.
    // Roles are written only alongside a newly created user, so a role revoked by hand in the
    // admin UI stays revoked instead of being re-granted at the next restart.
    private void seed(String email, String username, boolean emailVerified, boolean enabled, Role... roles) {
        if (userRepository.findByEmail(email).isPresent()) {
            return;
        }

        User user = userRepository.save(User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(DEV_PASSWORD))
                .emailVerified(emailVerified)
                .enabled(enabled)
                .build());

        for (Role role : roles) {
            userRoleRepository.save(UserRole.builder()
                    .user(user)
                    .role(role)
                    .build());
        }

        log.info("seeded dev user {} (roles={})", email, roles);
    }
}
