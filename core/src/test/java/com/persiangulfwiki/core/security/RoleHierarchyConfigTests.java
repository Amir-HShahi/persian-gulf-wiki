package com.persiangulfwiki.core.security;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, RoleHierarchyConfigTests.TestConfig.class})
@SpringBootTest
class RoleHierarchyConfigTests {

    @Autowired
    private ModeratorGuardedBean moderatorGuardedBean;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminAuthorityPassesModeratorOnlyCheckViaRoleHierarchy() {
        authenticateAs("ROLE_ADMIN");

        assertThat(moderatorGuardedBean.moderatorOnly()).isEqualTo("ok");
    }

    @Test
    void contributorAuthorityIsDeniedByModeratorOnlyCheck() {
        authenticateAs("ROLE_CONTRIBUTOR");

        assertThatThrownBy(moderatorGuardedBean::moderatorOnly)
                .isInstanceOf(AccessDeniedException.class);
    }

    private void authenticateAs(String authority) {
        GrantedAuthority granted = new SimpleGrantedAuthority(authority);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("user", null, List.of(granted)));
    }

    static class ModeratorGuardedBean {

        @PreAuthorize("hasRole('MODERATOR')")
        String moderatorOnly() {
            return "ok";
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ModeratorGuardedBean moderatorGuardedBean() {
            return new ModeratorGuardedBean();
        }
    }
}
