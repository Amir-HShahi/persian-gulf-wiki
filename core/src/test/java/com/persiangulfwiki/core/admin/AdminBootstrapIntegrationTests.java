package com.persiangulfwiki.core.admin;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.audit.repository.AuditLogRepository;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// Separate @SpringBootTest from AuthFlowIntegrationTests etc. so the ADMIN_BOOTSTRAP_EMAIL /
// ADMIN_BOOTSTRAP_PASSWORD properties (set only here via @DynamicPropertySource) get their own
// application context, rather than leaking a bootstrapped admin into every other test's context.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AdminBootstrapIntegrationTests {

    @DynamicPropertySource
    static void bootstrapProperties(DynamicPropertyRegistry registry) {
        registry.add("app.admin.bootstrap-email", () -> "admin@example.com");
        registry.add("app.admin.bootstrap-username", () -> "admin");
        registry.add("app.admin.bootstrap-password", () -> "Correct-Horse1!");
    }

    @Autowired
    private AdminBootstrapRunner adminBootstrapRunner;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @Transactional
    void bootstrapCreatesExactlyOneAdminWithAuditLogEntry() {
        assertThat(userRoleRepository.count()).isEqualTo(1L);
        assertThat(userRoleRepository.existsByRole(Role.ADMIN)).isTrue();

        assertThat(userRepository.findByEmail("admin@example.com"))
                .hasValueSatisfying(user -> assertThat(user.getUsername()).isEqualTo("admin"));

        assertThat(auditLogRepository.findAll())
                .hasSize(1)
                .allSatisfy(entry -> assertThat(entry.getAction()).isEqualTo("ADMIN_BOOTSTRAP"));
    }

    @Test
    @Transactional
    void rerunningBootstrapAfterRestartDoesNotDuplicateAdminOrAuditEntry() {
        // Context startup already ran the bootstrap once; invoking it again simulates a
        // second app restart against the same database state.
        adminBootstrapRunner.run(new DefaultApplicationArguments());

        assertThat(userRoleRepository.count()).isEqualTo(1L);
        assertThat(auditLogRepository.count()).isEqualTo(1L);
    }
}
