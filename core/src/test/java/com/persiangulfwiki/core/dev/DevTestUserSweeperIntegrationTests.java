package com.persiangulfwiki.core.dev;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.dev.dto.DevTestUserResponse;
import com.persiangulfwiki.core.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The negative TTL is what makes theScheduledEntryPointRunsInsideATransaction a real test:
// it puts the computed threshold an hour into the future, so the scheduled method matches
// every minted row and actually executes the delete. At the configured 6h the sweep matches
// nothing, the delete never runs, and the missing-transaction bug it guards against stays
// invisible. That property forks a context of its own rather than sharing
// DevTestUserEndpointIntegrationTests' — the cost of one more container-less context start,
// for the only test that exercises the path @Scheduled actually invokes.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "app.dev.test-user-ttl-hours=-1")
class DevTestUserSweeperIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DevTestUserSweeper devTestUserSweeper;

    private DevTestUserResponse mint() throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-users"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, DevTestUserResponse.class);
    }

    @Test
    void sweepsMintedUsersOlderThanTheThreshold() throws Exception {
        DevTestUserResponse response = mint();

        // A threshold in the future puts every existing row past it, which is how a row that
        // JPA cannot backdate (AuditableEntity maps created_at updatable = false and stamps it
        // in @PrePersist) can still be tested against the TTL boundary.
        long deleted = devTestUserSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.MINUTES));

        assertThat(deleted).isPositive();
        assertThat(userRepository.findById(response.userId())).isEmpty();
    }

    @Test
    void leavesMintedUsersYoungerThanTheThresholdAlone() throws Exception {
        DevTestUserResponse response = mint();

        long deleted = devTestUserSweeper.deleteOlderThan(Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(deleted).isZero();
        assertThat(userRepository.findById(response.userId())).isPresent();
    }

    @Test
    void theScheduledEntryPointRunsInsideATransaction() throws Exception {
        DevTestUserResponse response = mint();

        // Exercises the method @Scheduled actually calls, not the helper the other tests use.
        // Those call deleteOlderThan from outside the bean, so the proxy gives them a
        // transaction however the scheduled path is annotated; only this one crosses the
        // self-invocation inside sweepExpiredTestUsers, where deleteOlderThan's own
        // @Transactional is inert. Drop @Transactional from sweepExpiredTestUsers and this
        // fails with TransactionRequiredException — the failure that would otherwise surface
        // hourly in a dev environment and nowhere else.
        devTestUserSweeper.sweepExpiredTestUsers();

        assertThat(userRepository.findById(response.userId())).isEmpty();
    }

    @Test
    void neverSweepsSeededFixtureAccounts() throws Exception {
        // The distinction the whole prefix scheme exists for: seeded accounts share the
        // @dev.local domain and are older than any minted row, so a TTL sweep keyed on age
        // alone would delete exactly the accounts a human is using.
        mint();

        devTestUserSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.MINUTES));

        assertThat(userRepository.findByEmail("moderator@dev.local")).isPresent();
        assertThat(userRepository.findByEmail("admin@dev.local")).isPresent();
        assertThat(userRepository.findByEmail("disabled@dev.local")).isPresent();
    }
}
