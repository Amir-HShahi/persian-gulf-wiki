package com.persiangulfwiki.core.dev;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.dev.dto.DevTestUserRequest;
import com.persiangulfwiki.core.dev.dto.DevTestUserResponse;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs under the dev profile, the only profile in which DevTestUserController and
// DevSecurityConfig exist. Sharing the dev context with DevUserSeederIntegrationTests would
// mean each suite's users appearing underneath the other's count assertions, so this class
// deliberately asserts on the users it minted rather than on repository totals.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevTestUserEndpointIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private DevTestUserResponse mint(DevTestUserRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, DevTestUserResponse.class);
    }

    @Test
    void mintsAVerifiedEnabledContributorWithNoRequestBody() throws Exception {
        // No body at all — the shape a beforeEach hook uses when it just needs *a* user.
        String body = mockMvc.perform(post("/api/dev/test-users"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        DevTestUserResponse response = objectMapper.readValue(body, DevTestUserResponse.class);

        assertThat(response.email()).startsWith("e2e-").endsWith("@dev.local");
        assertThat(response.username()).startsWith("e2e_");
        assertThat(response.password()).isNotBlank();

        assertThat(userRepository.findById(response.userId())).hasValueSatisfying(user -> {
            assertThat(user.isEmailVerified()).isTrue();
            assertThat(user.isEnabled()).isTrue();
            // The plaintext exists only in the response; the row holds a BCrypt hash.
            assertThat(user.getPasswordHash()).isNotEqualTo(response.password()).startsWith("$2");
        });
        assertThat(userRoleRepository.findByUserId(response.userId()))
                .singleElement()
                .satisfies(userRole -> assertThat(userRole.getRole()).isEqualTo(Role.CONTRIBUTOR));
    }

    @Test
    void appliesRequestedRolesLiterallyWithNoImpliedContributorGrant() throws Exception {
        DevTestUserResponse response = mint(new DevTestUserRequest(List.of(Role.MODERATOR), null, null));

        assertThat(userRoleRepository.findByUserId(response.userId()))
                .singleElement()
                .satisfies(userRole -> assertThat(userRole.getRole()).isEqualTo(Role.MODERATOR));
    }

    @Test
    void reachesStatesTheRegistrationFlowCannotProduce() throws Exception {
        DevTestUserResponse response = mint(new DevTestUserRequest(null, false, false));

        assertThat(userRepository.findById(response.userId())).hasValueSatisfying(user -> {
            assertThat(user.isEmailVerified()).isFalse();
            assertThat(user.isEnabled()).isFalse();
        });
    }

    @Test
    void consecutiveCallsMintDistinctUsers() throws Exception {
        // The whole reason the endpoint exists: two parallel tests must never land on the
        // same account. username is citext, so a collision would be case-insensitive.
        DevTestUserResponse first = mint(new DevTestUserRequest(null, null, null));
        DevTestUserResponse second = mint(new DevTestUserRequest(null, null, null));

        assertThat(first.userId()).isNotEqualTo(second.userId());
        assertThat(first.email()).isNotEqualTo(second.email());
        assertThat(first.username()).isNotEqualTo(second.username());
        assertThat(first.password()).isNotEqualTo(second.password());
    }

    @Test
    void returnedCredentialsAreImmediatelyUsableForLogin() throws Exception {
        // The credentials being *returned* is worthless if they don't actually authenticate —
        // this is what a test harness does with the response one line later.
        DevTestUserResponse response = mint(new DevTestUserRequest(null, null, null));

        LoginRequest login = new LoginRequest(response.email(), response.password());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk());
    }

    @Test
    void needsNoCsrfTokenBecauseTheDevChainDisablesIt() throws Exception {
        // The main chain would reject this POST for a missing X-XSRF-TOKEN header. It never
        // sees it: DevSecurityConfig's chain claims /api/dev/** first and disables CSRF.
        mockMvc.perform(post("/api/dev/test-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void deletingAMintedUserRemovesItAndItsRoles() throws Exception {
        DevTestUserResponse response = mint(new DevTestUserRequest(List.of(Role.MODERATOR), null, null));

        mockMvc.perform(delete("/api/dev/test-users/{userId}", response.userId()))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(response.userId())).isEmpty();
        // user_roles.user_id is ON DELETE CASCADE (V3), so the role rows go with it — that is
        // what lets a teardown hook be one call rather than a cleanup sequence.
        assertThat(userRoleRepository.findByUserId(response.userId())).isEmpty();
    }

    @Test
    void deletingTheSameUserTwiceIsA404NotAnError() throws Exception {
        // Teardown hooks re-run. The second call has to be a clean, predictable answer rather
        // than a 500 the harness has to special-case.
        DevTestUserResponse response = mint(new DevTestUserRequest(null, null, null));

        mockMvc.perform(delete("/api/dev/test-users/{userId}", response.userId()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/dev/test-users/{userId}", response.userId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesToDeleteASeededFixtureAccount() throws Exception {
        // The guard that makes handing an arbitrary id to this route safe: a teardown hook
        // that lost track of its own id must not be able to delete the account a frontend
        // developer is currently logged into.
        UUID seededModeratorId = userRepository.findByEmail("moderator@dev.local").orElseThrow().getId();

        mockMvc.perform(delete("/api/dev/test-users/{userId}", seededModeratorId))
                .andExpect(status().isNotFound());

        assertThat(userRepository.findById(seededModeratorId)).isPresent();
    }

    @Test
    void deletingAnUnknownIdIsA404() throws Exception {
        mockMvc.perform(delete("/api/dev/test-users/{userId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
