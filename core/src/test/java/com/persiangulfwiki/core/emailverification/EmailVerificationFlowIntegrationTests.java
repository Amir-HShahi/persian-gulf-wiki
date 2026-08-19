package com.persiangulfwiki.core.emailverification;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.emailverification.dto.VerifyEmailRequest;
import com.persiangulfwiki.core.security.TokenHasher;
import com.persiangulfwiki.core.user.repository.EmailVerificationTokenRepository;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationFlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

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

    private record LoginCookies(Cookie accessToken, Cookie refreshToken) {
    }

    private LoginCookies registerAndLogin(String username, String email, String password) throws Exception {
        RegisterRequest register = new RegisterRequest(username, email, password);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        var loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        return new LoginCookies(loginResponse.getCookie("access_token"), loginResponse.getCookie("refresh_token"));
    }

    private String requestVerificationToken(Cookie accessTokenCookie) throws Exception {
        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/email-verification/resend")
                        .cookie(accessTokenCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk());

        return generatedTokens.get(generatedTokens.size() - 1);
    }

    private Cookie fetchCsrfCookie() throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        return csrfCookie;
    }

    // Mirrors PasswordFlowIntegrationTests/AuthFlowIntegrationTests: SecurityConfig's CsrfFilter
    // BREACH-masks the header value against the raw cookie token, so resending the cookie value
    // verbatim is rejected.
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
    void verifyEmailFullFlowMarksUserVerified() throws Exception {
        String email = "vera@example.com";
        LoginCookies cookies = registerAndLogin("vera", email, "Correct-Horse1!");
        String rawToken = requestVerificationToken(cookies.accessToken());

        mockMvc.perform(post("/api/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyEmailRequest(rawToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("آدرس ایمیل شما با موفقیت تأیید شد و حساب کاربری فعال گردید"));

        var user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    void verifyEmailRejectsExpiredToken() throws Exception {
        String email = "walt@example.com";
        LoginCookies cookies = registerAndLogin("walt", email, "Correct-Horse1!");
        String rawToken = requestVerificationToken(cookies.accessToken());

        var tokenRow = emailVerificationTokenRepository.findByTokenHash(tokenHasher.hash(rawToken)).orElseThrow();
        tokenRow.setExpiresAt(Instant.now().minusSeconds(60));
        emailVerificationTokenRepository.save(tokenRow);

        mockMvc.perform(post("/api/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyEmailRequest(rawToken))))
                .andExpect(status().isUnauthorized());

        var user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void verifyEmailRejectsReuseOfSameToken() throws Exception {
        String email = "xena@example.com";
        LoginCookies cookies = registerAndLogin("xena", email, "Correct-Horse1!");
        String rawToken = requestVerificationToken(cookies.accessToken());

        mockMvc.perform(post("/api/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyEmailRequest(rawToken))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyEmailRequest(rawToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resendDispatchesExactlyOneEmailContainingRawToken() throws Exception {
        String email = "yara@example.com";
        LoginCookies cookies = registerAndLogin("yara", email, "Correct-Horse1!");

        // register() also dispatches its own verification email; isolate the assertion
        // below to just the resend call's dispatch.
        clearInvocations(javaMailSender);

        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/email-verification/resend")
                        .cookie(cookies.accessToken(), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk());

        String rawToken = generatedTokens.get(generatedTokens.size() - 1);

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender, timeout(2000).times(1)).send(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText()).contains(rawToken);
    }

    @Test
    void verifyEmailWithoutCsrfTokenStillSucceeds() throws Exception {
        String email = "zack@example.com";
        LoginCookies cookies = registerAndLogin("zack", email, "Correct-Horse1!");
        String rawToken = requestVerificationToken(cookies.accessToken());

        mockMvc.perform(post("/api/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyEmailRequest(rawToken))))
                .andExpect(status().isOk());
    }

    @Test
    void verifiedSessionSelfHealsAfterRefreshAndCanReachGatedEndpoint() throws Exception {
        String email = "bruno@example.com";
        LoginCookies cookies = registerAndLogin("bruno", email, "Correct-Horse1!");

        // Before verification: gated endpoint is blocked.
        mockMvc.perform(get("/api/users/me/sessions").cookie(cookies.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        String rawToken = requestVerificationToken(cookies.accessToken());
        mockMvc.perform(post("/api/email-verification/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerifyEmailRequest(rawToken))))
                .andExpect(status().isOk());

        // The already-issued access token's `verified` claim is stale until refresh — the
        // old cookie still gets blocked immediately after verification.
        mockMvc.perform(get("/api/users/me/sessions").cookie(cookies.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        Cookie csrfCookie = fetchCsrfCookie();
        var refreshResponse = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(cookies.refreshToken(), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Cookie refreshedAccessCookie = refreshResponse.getCookie("access_token");
        assertThat(refreshedAccessCookie).isNotNull();

        mockMvc.perform(get("/api/users/me/sessions").cookie(refreshedAccessCookie))
                .andExpect(status().isOk());
    }

    @Test
    void resendWithoutCsrfTokenIsRejectedThenSucceedsWithToken() throws Exception {
        String email = "amber@example.com";
        LoginCookies cookies = registerAndLogin("amber", email, "Correct-Horse1!");

        mockMvc.perform(post("/api/email-verification/resend")
                        .cookie(cookies.accessToken()))
                .andExpect(status().isForbidden());

        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(post("/api/email-verification/resend")
                        .cookie(cookies.accessToken(), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk());
    }
}
