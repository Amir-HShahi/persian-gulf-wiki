package com.persiangulfwiki.core.oauth2;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.oauth2.dto.CompleteOAuthRegistrationRequest;
import com.persiangulfwiki.core.security.JwtService;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OAuth2FlowIntegrationTests {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    // Same shape GoogleOAuth2SuccessHandler's Case D produces: no username yet,
    // no password, email already verified by Google.
    private User createPendingUser(String email) {
        User user = User.builder()
                .email(email)
                .passwordHash(null)
                .emailVerified(true)
                .build();
        return userRepository.save(user);
    }

    private Cookie pendingCookie(UUID userId) {
        String token = jwtService.generatePendingPasswordSetupToken(userId);
        return new Cookie("access_token", token);
    }

    private Cookie fetchCsrfCookie() throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        return csrfCookie;
    }

    // Mirrors PasswordFlowIntegrationTests/AuthFlowIntegrationTests: SecurityConfig's
    // CsrfFilter BREACH-masks the header value against the raw cookie token, so resending
    // the cookie value verbatim is rejected.
    private String maskCsrfToken(String rawToken) {
        byte[] tokenBytes = rawToken.getBytes(StandardCharsets.UTF_8);
        byte[] random = new byte[tokenBytes.length];
        new SecureRandom().nextBytes(random);
        byte[] xored = new byte[tokenBytes.length];
        for (int i = 0; i < tokenBytes.length; i++) {
            xored[i] = (byte) (random[i] ^ tokenBytes[i]);
        }
        byte[] combined = new byte[random.length + xored.length];
        System.arraycopy(random, 0, combined, 0, random.length);
        System.arraycopy(xored, 0, combined, random.length, xored.length);
        return Base64.getUrlEncoder().encodeToString(combined);
    }

    @Test
    void completeRegistrationFullFlowIssuesRealSessionAndClearsPendingState() throws Exception {
        String email = "pending-full@example.com";
        User user = createPendingUser(email);
        Cookie pending = pendingCookie(user.getId());

        Cookie csrfCookie = fetchCsrfCookie();
        var response = mockMvc.perform(post("/api/auth/oauth2/complete-registration")
                        .cookie(pending, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteOAuthRegistrationRequest("pending-full-user", "Correct-Horse1!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        Cookie newAccessToken = response.getCookie("access_token");
        Cookie newRefreshToken = response.getCookie("refresh_token");
        assertThat(newAccessToken).isNotNull();
        assertThat(newRefreshToken).isNotNull();

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isNotNull();
        assertThat(reloaded.getUsername()).isEqualTo("pending-full-user");

        mockMvc.perform(get("/api/users/me").cookie(newAccessToken))
                .andExpect(status().isOk());
    }

    @Test
    void completeRegistrationWithoutCsrfTokenIsRejected() throws Exception {
        String email = "pending-nocsrf@example.com";
        User user = createPendingUser(email);
        Cookie pending = pendingCookie(user.getId());

        mockMvc.perform(post("/api/auth/oauth2/complete-registration")
                        .cookie(pending)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteOAuthRegistrationRequest("pending-nocsrf-user", "Correct-Horse1!"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void completeRegistrationWithWeakPasswordReturnsBadRequest() throws Exception {
        String email = "pending-weak@example.com";
        User user = createPendingUser(email);
        Cookie pending = pendingCookie(user.getId());

        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/auth/oauth2/complete-registration")
                        .cookie(pending, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteOAuthRegistrationRequest("pending-weak-user", "weak"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeRegistrationWithBlankUsernameReturnsBadRequest() throws Exception {
        String email = "pending-blank-username@example.com";
        User user = createPendingUser(email);
        Cookie pending = pendingCookie(user.getId());

        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/auth/oauth2/complete-registration")
                        .cookie(pending, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteOAuthRegistrationRequest("", "Correct-Horse1!"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeRegistrationWithMalformedUsernameReturnsBadRequest() throws Exception {
        String email = "pending-malformed-username@example.com";
        User user = createPendingUser(email);
        Cookie pending = pendingCookie(user.getId());

        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/auth/oauth2/complete-registration")
                        .cookie(pending, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteOAuthRegistrationRequest("bad!user", "Correct-Horse1!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].message").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("صرفاً شامل حروف انگلیسی، اعداد، زیرخط"))));
    }

    @Test
    void completeRegistrationWithUsernameAlreadyTakenReturnsConflict() throws Exception {
        String takenUsername = "already-taken-user";
        RegisterRequest register = new RegisterRequest(takenUsername, "username-taken-owner@example.com",
                "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        String email = "pending-username-taken@example.com";
        User user = createPendingUser(email);
        Cookie pending = pendingCookie(user.getId());

        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/auth/oauth2/complete-registration")
                        .cookie(pending, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteOAuthRegistrationRequest(takenUsername, "Correct-Horse1!"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("این نام کاربری پیش‌تر توسط کاربر دیگری در سامانه ثبت شده است؛ لطفاً نام دیگری انتخاب فرمایید"));
    }

    @Test
    void completeRegistrationWithNormalSessionForAlreadyCompletedAccountReturnsConflict() throws Exception {
        String username = "already-complete";
        String email = "already-complete@example.com";
        RegisterRequest register = new RegisterRequest(username, email, "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);

        var loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "Correct-Horse1!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Cookie normalAccessToken = loginResponse.getCookie("access_token");
        assertThat(normalAccessToken).isNotNull();

        // A normal access token carries no `scope` claim, so PendingPasswordSetupFilter
        // doesn't block it here — the service-layer guard is what must catch this.
        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/auth/oauth2/complete-registration")
                        .cookie(normalAccessToken, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteOAuthRegistrationRequest("another-username", "another-Correct-Horse1!"))))
                .andExpect(status().isConflict());
    }

    @Test
    void completeRegistrationCalledTwiceWithSameStalePendingCookieReturnsConflictSecondTime() throws Exception {
        String email = "pending-twice@example.com";
        User user = createPendingUser(email);
        Cookie pending = pendingCookie(user.getId());

        Cookie firstCsrf = fetchCsrfCookie();
        mockMvc.perform(post("/api/auth/oauth2/complete-registration")
                        .cookie(pending, firstCsrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(firstCsrf.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteOAuthRegistrationRequest("pending-twice-user", "Correct-Horse1!"))))
                .andExpect(status().isOk());

        // The pending cookie is still validly signed and unexpired, so
        // PendingPasswordSetupFilter still lets it through — the second 409 must come
        // from OAuth2Service seeing a non-null password_hash, not a silent success.
        Cookie secondCsrf = fetchCsrfCookie();
        mockMvc.perform(post("/api/auth/oauth2/complete-registration")
                        .cookie(pending, secondCsrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(secondCsrf.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteOAuthRegistrationRequest("another-username", "another-Correct-Horse1!"))))
                .andExpect(status().isConflict());
    }
}
