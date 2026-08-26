package com.persiangulfwiki.core.admin;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

// No ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD set here, mirroring a normal
// deployment: the app must start cleanly and create no admin.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AdminBootstrapNotConfiguredIntegrationTests {

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Test
    void applicationStartsWithoutBootstrappingAnAdmin() {
        assertThat(userRoleRepository.existsByRole(Role.ADMIN)).isFalse();
    }
}
