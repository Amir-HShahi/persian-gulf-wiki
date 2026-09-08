package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.user.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The half of the contract that actually matters for review: no dev profile, therefore neither
// gate is in place, therefore /api/dev/** is not reachable. Runs under the default profile —
// the same context configuration as every production-shaped integration test in this suite.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DevTestUserEndpointDisabledIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void neitherDevBeanIsRegisteredOutsideTheDevProfile() {
        // Gate one. Both are @Profile("dev"), so outside dev they do not merely skip their
        // work — they do not exist, and there is nothing to misconfigure back into life.
        assertThat(applicationContext.getBeanNamesForType(DevTestUserController.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(DevSecurityConfig.class)).isEmpty();
        // The sweeper matters most of the three: it is the only one that deletes rows, and it
        // fires on a schedule rather than on a request, so an unprofiled copy would run in
        // production with nothing to trigger it and nothing to notice.
        assertThat(applicationContext.getBeanNamesForType(DevTestUserSweeper.class)).isEmpty();
    }

    @Test
    void devRoutesFallThroughToTheMainChainAndAreRejectedUnauthenticated() throws Exception {
        // Gate two. With DevSecurityConfig absent, no securityMatcher claims /api/dev/**, so
        // the request reaches SecurityConfig's anyRequest().authenticated(). Authorization
        // runs before routing, so this is a 401 rather than the 404 the missing controller
        // would otherwise produce — the path never even resolves to "not mapped".
        mockMvc.perform(get("/api/dev/test-users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dev/test-users/{userId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mintingIsRejectedAndCreatesNothing() throws Exception {
        long usersBefore = userRepository.count();

        // 403, not 401: the main chain's CSRF filter runs ahead of the authorization filter
        // and /api/dev/** is not in its ignoringRequestMatchers list, so an unauthenticated
        // POST is refused for the missing X-XSRF-TOKEN header first. Either status is a
        // rejection; what the test pins down is that the request cannot mint a user.
        mockMvc.perform(post("/api/dev/test-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }
}
