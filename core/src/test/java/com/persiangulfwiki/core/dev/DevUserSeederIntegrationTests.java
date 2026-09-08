package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// Runs under the dev profile — the only profile in which the seeder bean exists at all — so
// this context is deliberately separate from every other @SpringBootTest, which must not have
// six seeded users appear underneath them.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("dev")
class DevUserSeederIntegrationTests {

    @Autowired
    private DevUserSeeder devUserSeeder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Test
    @Transactional
    void seedsEveryAccountStateTheFrontendHasToRender() {
        assertThat(userRepository.count()).isEqualTo(6L);

        assertThat(userRepository.findByEmail("unverified@dev.local"))
                .hasValueSatisfying(user -> assertThat(user.isEmailVerified()).isFalse());
        assertThat(userRepository.findByEmail("disabled@dev.local"))
                .hasValueSatisfying(user -> assertThat(user.isEnabled()).isFalse());

        assertThat(userRoleRepository.existsByRole(Role.MODERATOR)).isTrue();
        assertThat(userRoleRepository.existsByRole(Role.EXPERT_REVIEWER)).isTrue();
        assertThat(userRoleRepository.existsByRole(Role.ADMIN)).isTrue();
        assertThat(userRoleRepository.countByRole(Role.CONTRIBUTOR)).isEqualTo(6L);
    }

    @Test
    @Transactional
    void rerunningAgainstASeededDatabaseIsANoOp() {
        // The compose Postgres volume survives restarts, so this is the normal case: every
        // boot after the first re-runs the seeder against data that is already there.
        devUserSeeder.run(new DefaultApplicationArguments());

        assertThat(userRepository.count()).isEqualTo(6L);
        assertThat(userRoleRepository.count()).isEqualTo(9L);
    }
}
