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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PendingPasswordSetupFilterTests {

    private static final String PROTECTED_ENDPOINT = "/actuator/health";
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
