package com.persiangulfwiki.core.security;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.user.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PendingPasswordSetupFilterTests {

    // Any authenticated route off both the pending allowlist and PublicRoutes. Not a public
    // route like /actuator/health: those read as anonymous for a pending token instead of 403.
    private static final String PROTECTED_ENDPOINT = "/api/users/me";
    private static final String ALLOWLISTED_ENDPOINT = "/api/auth/csrf";
    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void pendingScopeTokenIsAllowedOnAllowlistedPath() throws Exception {
        String token = jwtService.generatePendingPasswordSetupToken(UUID.randomUUID());

        int responseStatus = mockMvc.perform(get(ALLOWLISTED_ENDPOINT)
                        .cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, token))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(responseStatus).isNotEqualTo(403);
    }

    @Test
    void pendingScopeTokenIsBlockedOnNonAllowlistedPath() throws Exception {
        String token = jwtService.generatePendingPasswordSetupToken(UUID.randomUUID());

        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_SETUP_REQUIRED"));
    }

    @Test
    void pendingScopeTokenIsBlockedOnRealBusinessEndpoint() throws Exception {
        String token = jwtService.generatePendingPasswordSetupToken(UUID.randomUUID());

        mockMvc.perform(get("/api/users/me/sessions")
                        .cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_SETUP_REQUIRED"));
    }

    // A public route reads as anonymous for a pending token instead of 403 -- the same result
    // as sending no cookie at all.
    @Test
    void pendingScopeTokenReadsPublicRoutesAsAnonymous() throws Exception {
        String token = jwtService.generatePendingPasswordSetupToken(UUID.randomUUID());

        for (String publicPath : List.of("/api/subjects", "/api/sources", "/v3/api-docs", "/docs/index.html",
                "/actuator/health")) {
            mockMvc.perform(get(publicPath).cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, token)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/articles").param("language", "fa")
                        .cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, token)))
                .andExpect(status().isOk());
    }

    // Public reads are GET-only: a write on the same path is still refused as a pending token,
    // not demoted to anonymous (which would surface as a 401 instead).
    @Test
    void pendingScopeTokenIsStillBlockedFromWritesOnPublicPaths() throws Exception {
        String token = jwtService.generatePendingPasswordSetupToken(UUID.randomUUID());

        mockMvc.perform(post("/api/subjects")
                        .with(xsrf())
                        .cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_SETUP_REQUIRED"));
    }

    @Test
    void normalAccessTokenIsUnaffectedByThisFilter() throws Exception {
        String token = jwtService.generateAccessToken(UUID.randomUUID(), List.of(Role.CONTRIBUTOR), true);

        int responseStatus = mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .cookie(new jakarta.servlet.http.Cookie(ACCESS_TOKEN_COOKIE, token))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(responseStatus).isNotEqualTo(403);
    }
}
