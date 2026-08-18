package com.persiangulfwiki.core.password;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.password.dto.ForgotPasswordRequest;
import com.persiangulfwiki.core.password.dto.ResetPasswordRequest;
import com.persiangulfwiki.core.security.TokenHasher;
import com.persiangulfwiki.core.user.dto.ChangePasswordRequest;
import com.persiangulfwiki.core.user.repository.PasswordResetTokenRepository;
import com.persiangulfwiki.core.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PasswordFlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @MockitoSpyBean
    private TokenHasher tokenHasher;

    @MockitoBean
    private JavaMailSender javaMailSender;

    private final List<String> generatedTokens = new ArrayList<>();

    @BeforeEach
    void captureGeneratedTokens() {
        generatedTokens.clear();
        doAnswer(invocation -> {
            String rawToken = (String) invocation.callRealMethod();
            generatedTokens.add(rawToken);
            return rawToken;
        }).when(tokenHasher).generateToken();
    }

    private String registerAndRequestResetToken(String username, String email, String password) throws Exception {
        RegisterRequest register = new RegisterRequest(username, email, password);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        // These tests exercise password reset/change, not email verification — mark the
        // account verified directly so tokens minted after this point clear
        // EmailVerificationRequiredFilter's gate.
        var user = userRepository.findByEmail(email.toLowerCase(java.util.Locale.ROOT)).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);

        // register() also dispatches its own verification email; isolate any later
        // send-count assertion to just the forgot-password call's dispatch.
        clearInvocations(javaMailSender);

        mockMvc.perform(post("/api/password/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordRequest(email))))
                .andExpect(status().isOk());

        return generatedTokens.get(generatedTokens.size() - 1);
    }

    @Test
    void forgotPasswordForKnownEmailCreatesResetToken() throws Exception {
        RegisterRequest register = new RegisterRequest("olivia", "olivia@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        ForgotPasswordRequest request = new ForgotPasswordRequest("olivia@example.com");
        mockMvc.perform(post("/api/password/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        var user = userRepository.findByEmail("olivia@example.com").orElseThrow();
        assertThat(passwordResetTokenRepository.findAll())
                .anyMatch(token -> token.getUser().getId().equals(user.getId()));
    }

    @Test
    void forgotPasswordForUnknownEmailReturnsNotFound() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest("nobody@example.com");
        mockMvc.perform(post("/api/password/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void forgotPasswordWithoutCsrfTokenStillSucceeds() throws Exception {
        RegisterRequest register = new RegisterRequest("peter", "peter@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        ForgotPasswordRequest request = new ForgotPasswordRequest("peter@example.com");
        mockMvc.perform(post("/api/password/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void forgotPasswordForKnownEmailDispatchesEmail() throws Exception {
        String email = "nora@example.com";
        RegisterRequest register = new RegisterRequest("nora", email, "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        // register() also dispatches its own verification email; isolate the assertion
        // below to just the forgot-password call's dispatch.
        clearInvocations(javaMailSender);

        mockMvc.perform(post("/api/password/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordRequest(email))))
                .andExpect(status().isOk());

        verify(javaMailSender, timeout(2000).times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void forgotPasswordForUnknownEmailDoesNotDispatchEmail() throws Exception {
        mockMvc.perform(post("/api/password/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("nobody2@example.com"))))
                .andExpect(status().isNotFound());

        verify(javaMailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void forgotPasswordEmailContainsRawTokenNotHash() throws Exception {
        String email = "opal@example.com";
        String rawResetToken = registerAndRequestResetToken("opal", email, "Correct-Horse1!");

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender, timeout(2000).times(1)).send(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText()).contains(rawResetToken);
    }

    @Test
    void resetPasswordFullFlowRevokesOldSessionAndUpdatesCredential() throws Exception {
        String email = "frank@example.com";
        String rawResetToken = registerAndRequestResetToken("frank", email, "Correct-Horse1!");

        String refreshCookieBeforeReset = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "Correct-Horse1!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token").getValue();

        mockMvc.perform(post("/api/password/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest(rawResetToken, "new-Correct-Horse1!"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "Correct-Horse1!"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "new-Correct-Horse1!"))))
                .andExpect(status().isOk());

        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshCookieBeforeReset), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isUnauthorized());
    }

    private Cookie fetchCsrfCookie() throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        return csrfCookie;
    }

    // Mirrors AuthFlowIntegrationTests: SecurityConfig's CsrfFilter BREACH-masks the header
    // value against the raw cookie token, so resending the cookie value verbatim is rejected.
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
    void resetPasswordRejectsReuseOfSameToken() throws Exception {
        String email = "grace@example.com";
        String rawResetToken = registerAndRequestResetToken("grace", email, "Correct-Horse1!");

        mockMvc.perform(post("/api/password/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest(rawResetToken, "new-Correct-Horse1!"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/password/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest(rawResetToken, "another-Correct-Horse1!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordRejectsExpiredToken() throws Exception {
        String email = "heidi@example.com";
        String rawResetToken = registerAndRequestResetToken("heidi", email, "Correct-Horse1!");

        var tokenRow = passwordResetTokenRepository.findByTokenHash(tokenHasher.hash(rawResetToken)).orElseThrow();
        tokenRow.setExpiresAt(Instant.now().minusSeconds(60));
        passwordResetTokenRepository.save(tokenRow);

        mockMvc.perform(post("/api/password/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest(rawResetToken, "new-Correct-Horse1!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordRejectsUnknownToken() throws Exception {
        mockMvc.perform(post("/api/password/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest("not-a-real-token", "new-Correct-Horse1!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordWithoutCsrfTokenStillSucceeds() throws Exception {
        String email = "ivan@example.com";
        String rawResetToken = registerAndRequestResetToken("ivan", email, "Correct-Horse1!");

        mockMvc.perform(post("/api/password/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest(rawResetToken, "new-Correct-Horse1!"))))
                .andExpect(status().isOk());
    }

    private LoginCookies registerAndLogin(String username, String email, String password) throws Exception {
        RegisterRequest register = new RegisterRequest(username, email, password);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        // These tests exercise the password-management endpoints, not email verification —
        // mark the account verified directly so the login below mints a token whose
        // `verified` claim lets it clear EmailVerificationRequiredFilter's gate.
        var user = userRepository.findByEmail(email.toLowerCase(java.util.Locale.ROOT)).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);

        var loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        return new LoginCookies(loginResponse.getCookie("access_token"), loginResponse.getCookie("refresh_token"));
    }

    private record LoginCookies(Cookie accessToken, Cookie refreshToken) {
    }

    @Test
    void changePasswordWithCorrectCurrentPasswordUpdatesCredentialAndRevokesSessions() throws Exception {
        String email = "judy@example.com";
        LoginCookies cookies = registerAndLogin("judy", email, "Correct-Horse1!");

        Cookie csrfCookie = fetchCsrfCookie();
        // Same posture as logout/logout-all: change-password clears the calling session's own
        // cookies too, so the caller must re-login rather than keep using its access token.
        mockMvc.perform(post("/api/users/me/change-password")
                        .cookie(cookies.accessToken(), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("Correct-Horse1!", "new-Correct-Horse1!"))))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("access_token", 0))
                .andExpect(cookie().maxAge("refresh_token", 0));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "Correct-Horse1!"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "new-Correct-Horse1!"))))
                .andExpect(status().isOk());

        Cookie refreshCsrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(cookies.refreshToken(), refreshCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(refreshCsrfCookie.getValue())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullPasswordManagementFlowAcrossAllFourEndpoints() throws Exception {
        String email = "olga@example.com";
        String originalPassword = "Correct-Horse1!";
        String resetPassword = "new-Correct-Horse1!";
        String changedPassword = "newest-Correct-Horse1!";

        String rawResetToken = registerAndRequestResetToken("olga", email, originalPassword);

        mockMvc.perform(post("/api/password/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ResetPasswordRequest(rawResetToken, resetPassword))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, originalPassword))))
                .andExpect(status().isUnauthorized());

        LoginCookies cookiesAfterReset = loginOnly(email, resetPassword);

        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/users/me/change-password")
                        .cookie(cookiesAfterReset.accessToken(), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(resetPassword, changedPassword))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, resetPassword))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, changedPassword))))
                .andExpect(status().isOk());
    }

    private LoginCookies loginOnly(String email, String password) throws Exception {
        var loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return new LoginCookies(loginResponse.getCookie("access_token"), loginResponse.getCookie("refresh_token"));
    }

    @Test
    void changePasswordWithWrongCurrentPasswordReturnsUnauthorizedAndLeavesPasswordUnchanged() throws Exception {
        String email = "karl@example.com";
        LoginCookies cookies = registerAndLogin("karl", email, "Correct-Horse1!");

        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/users/me/change-password")
                        .cookie(cookies.accessToken(), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("wrong-password", "new-Correct-Horse1!"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, "Correct-Horse1!"))))
                .andExpect(status().isOk());
    }

    @Test
    void changePasswordWithoutAuthCookieReturnsUnauthorized() throws Exception {
        // CSRF is checked before authentication, so a valid CSRF token must be supplied here
        // to isolate the auth-cookie check rather than getting a 403 from the CSRF filter first.
        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/users/me/change-password")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("Correct-Horse1!", "new-Correct-Horse1!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePasswordWithoutCsrfTokenIsRejected() throws Exception {
        String email = "linda@example.com";
        LoginCookies cookies = registerAndLogin("linda", email, "Correct-Horse1!");

        mockMvc.perform(post("/api/users/me/change-password")
                        .cookie(cookies.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("Correct-Horse1!", "new-Correct-Horse1!"))))
                .andExpect(status().isForbidden());
    }
}
