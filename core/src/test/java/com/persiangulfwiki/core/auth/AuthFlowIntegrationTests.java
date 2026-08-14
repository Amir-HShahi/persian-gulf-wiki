package com.persiangulfwiki.core.auth;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.emailverification.service.EmailVerificationService;
import com.persiangulfwiki.core.security.TokenHasher;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private TokenHasher tokenHasher;

    @MockitoSpyBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @org.springframework.beans.factory.annotation.Value("${app.oauth2.abandoned-account-ttl-hours}")
    private long abandonedAccountTtlHours;

    private final List<String> generatedTokens = new ArrayList<>();

    // Seeds a password_hash IS NULL row (the shape GoogleOAuth2SuccessHandler's Case D
    // produces) and backdates its createdAt. createdAt is updatable=false on
    // AuditableEntity, so a plain setter + repository.save() wouldn't persist the backdate —
    // a JPQL bulk update bypasses that restriction. Run in its own transaction since this
    // test class has none open by default.
    private User seedAbandonedGoogleUser(String email, java.time.Instant createdAt) {
        User user = User.builder()
                .email(email)
                .passwordHash(null)
                .emailVerified(true)
                .build();
        User saved = userRepository.save(user);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createQuery("update User u set u.createdAt = :createdAt where u.id = :id")
                        .setParameter("createdAt", createdAt)
                        .setParameter("id", saved.getId())
                        .executeUpdate());

        return saved;
    }

    @BeforeEach
    void captureGeneratedTokens() {
        generatedTokens.clear();
        doAnswer(invocation -> {
            String rawToken = (String) invocation.callRealMethod();
            generatedTokens.add(rawToken);
            return rawToken;
        }).when(tokenHasher).generateToken();
    }

    @Test
    void registerLoginThenMeReturnsRegisteredProfile() throws Exception {
        RegisterRequest register = new RegisterRequest("frank", "Frank@Example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("Frank@Example.com", "Correct-Horse1!");
        Cookie accessTokenCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");

        assertThat(accessTokenCookie).isNotNull();

        String meBody = mockMvc.perform(get("/api/users/me").cookie(accessTokenCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("frank"))
                .andExpect(jsonPath("$.data.email").value("frank@example.com"))
                .andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.roles", org.hamcrest.Matchers.hasItem("CONTRIBUTOR")))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String id = com.jayway.jsonpath.JsonPath.read(meBody, "$.data.id");
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void meWithTamperedCookieIsRejected() throws Exception {
        RegisterRequest register = new RegisterRequest("grace", "grace@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("grace@example.com", "Correct-Horse1!");
        Cookie accessTokenCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");

        assertThat(accessTokenCookie).isNotNull();

        String validToken = accessTokenCookie.getValue();
        int flipIndex = validToken.length() / 2;
        char flipped = validToken.charAt(flipIndex) == 'a' ? 'b' : 'a';
        String tamperedToken = validToken.substring(0, flipIndex) + flipped + validToken.substring(flipIndex + 1);
        Cookie tamperedCookie = new Cookie("access_token", tamperedToken);

        mockMvc.perform(get("/api/users/me").cookie(tamperedCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesTokenAndRejectsReuseOfOldOne() throws Exception {
        RegisterRequest register = new RegisterRequest("heidi", "heidi@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("heidi@example.com", "Correct-Horse1!");
        var loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Cookie originalRefreshCookie = loginResponse.getCookie("refresh_token");
        assertThat(originalRefreshCookie).isNotNull();

        // Double-submit CSRF token is stateless, so the same cookie/header pair is valid
        // for every POST in this test — no need to re-fetch it before each call.
        Cookie csrfCookie = fetchCsrfCookie();

        var firstRefreshResponse = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(originalRefreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().exists("refresh_token"))
                .andReturn().getResponse();
        Cookie rotatedRefreshCookie = firstRefreshResponse.getCookie("refresh_token");
        assertThat(rotatedRefreshCookie).isNotNull();
        assertThat(rotatedRefreshCookie.getValue()).isNotEqualTo(originalRefreshCookie.getValue());

        // The original token was burned by the rotation above, so replaying it must fail —
        // this is what distinguishes reuse detection from a generic invalid-token check.
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(originalRefreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(rotatedRefreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesRefreshTokenSoSubsequentRefreshFails() throws Exception {
        RegisterRequest register = new RegisterRequest("ivan", "ivan@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("ivan@example.com", "Correct-Horse1!");
        Cookie refreshCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        assertThat(refreshCookie).isNotNull();

        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(refreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("access_token", 0))
                .andExpect(cookie().maxAge("refresh_token", 0));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithoutCsrfTokenIsRejected() throws Exception {
        RegisterRequest register = new RegisterRequest("judy", "judy@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("judy@example.com", "Correct-Horse1!");
        Cookie refreshCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        assertThat(refreshCookie).isNotNull();

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshWithUnmaskedCsrfTokenIsRejected() throws Exception {
        RegisterRequest register = new RegisterRequest("kevin", "kevin@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("kevin@example.com", "Correct-Horse1!");
        Cookie refreshCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        assertThat(refreshCookie).isNotNull();

        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutAllRevokesEverySessionSoAllPendingRefreshesFail() throws Exception {
        RegisterRequest register = new RegisterRequest("mallory", "mallory@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("mallory@example.com", "Correct-Horse1!");
        var firstLoginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Cookie accessCookie = firstLoginResponse.getCookie("access_token");
        Cookie firstRefreshCookie = firstLoginResponse.getCookie("refresh_token");
        assertThat(accessCookie).isNotNull();
        assertThat(firstRefreshCookie).isNotNull();

        Cookie secondRefreshCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        assertThat(secondRefreshCookie).isNotNull();

        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(post("/api/auth/logout-all")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("access_token", 0))
                .andExpect(cookie().maxAge("refresh_token", 0));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(firstRefreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(secondRefreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllWithoutCsrfTokenIsRejected() throws Exception {
        RegisterRequest register = new RegisterRequest("niaj", "niaj@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("niaj@example.com", "Correct-Horse1!");
        Cookie accessCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
        assertThat(accessCookie).isNotNull();

        mockMvc.perform(post("/api/auth/logout-all")
                        .cookie(accessCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerDispatchesExactlyOneVerificationEmailContainingRawToken() throws Exception {
        RegisterRequest register = new RegisterRequest("oscar", "oscar@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        String rawToken = generatedTokens.get(generatedTokens.size() - 1);

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender, timeout(2000).times(1)).send(messageCaptor.capture());

        assertThat(messageCaptor.getValue().getText()).contains(rawToken);
    }

    @Test
    void registerResponseIncludesVerificationEmailMessage() throws Exception {
        RegisterRequest register = new RegisterRequest("penny", "penny@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(
                        "ثبت‌نام شما با موفقیت انجام شد. برای فعال‌سازی حساب کاربری، لطفاً ایمیل ارسالی را بررسی و آدرس ایمیل خود را تأیید فرمایید"));
    }

    @Test
    void registerStillSucceedsWhenVerificationEmailDispatchFails() throws Exception {
        doThrow(new RuntimeException("simulated dispatch failure"))
                .when(emailVerificationService).sendVerification(any());

        RegisterRequest register = new RegisterRequest("quinn", "quinn@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("quinn"))
                .andExpect(jsonPath("$.data.email").value("quinn@example.com"))
                .andExpect(jsonPath("$.message").isNotEmpty());

        assertThat(userRepository.findByEmail("quinn@example.com")).isPresent();
    }

    @Test
    void registerSetsAccessAndRefreshCookiesLikeLogin() throws Exception {
        RegisterRequest register = new RegisterRequest("sybil", "sybil@example.com", "Correct-Horse1!");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().httpOnly("access_token", true))
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().httpOnly("refresh_token", true));
    }

    @Test
    void registerReclaimsStaleAbandonedGoogleOnlyRowPastTtl() throws Exception {
        String email = "stale-abandoned@example.com";
        User stale = seedAbandonedGoogleUser(email,
                java.time.Instant.now().minus(abandonedAccountTtlHours + 1, java.time.temporal.ChronoUnit.HOURS));

        RegisterRequest register = new RegisterRequest("staleuser", email, "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        assertThat(userRepository.findById(stale.getId())).isEmpty();
        assertThat(userRepository.findByEmail(email)).hasValueSatisfying(
                found -> assertThat(found.getPasswordHash()).isNotNull());
    }

    @Test
    void registerStillRejectsNonStaleAbandonedGoogleOnlyRowWithinTtl() throws Exception {
        String email = "fresh-abandoned@example.com";
        seedAbandonedGoogleUser(email,
                java.time.Instant.now().minus(abandonedAccountTtlHours - 1, java.time.temporal.ChronoUnit.HOURS));

        RegisterRequest register = new RegisterRequest("freshuser", email, "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isConflict());
    }

    @Test
    void unverifiedUserFromRegisterCanReachAllowlistedRoutesButIsBlockedElsewhere() throws Exception {
        RegisterRequest register = new RegisterRequest("trent", "trent@example.com", "Correct-Horse1!");
        var registerResponse = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        Cookie accessCookie = registerResponse.getCookie("access_token");
        assertThat(accessCookie).isNotNull();

        // Allowlisted even though unverified.
        mockMvc.perform(get("/api/users/me").cookie(accessCookie))
                .andExpect(status().isOk());

        // Not allowlisted: blocked with a machine-readable error code.
        mockMvc.perform(get("/api/users/me/sessions").cookie(accessCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void preflightFromAllowedOriginIsAccepted() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void preflightFromUnlistedOriginIsRejected() throws Exception {
        // Spring's CorsFilter doesn't reject a disallowed origin with a 4xx status — it just
        // omits the Access-Control-Allow-Origin header, which is what makes the browser itself
        // block the response. Verified by running this test against the current CorsConfig.
        mockMvc.perform(options("/api/users/me")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void corsAllowedHeadersIncludeXsrfTokenForCrossOriginRefresh() throws Exception {
        mockMvc.perform(options("/api/auth/refresh")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-XSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("X-XSRF-TOKEN")));
    }

    @Test
    void crossOriginRefreshWithCsrfHeaderStillSucceeds() throws Exception {
        RegisterRequest register = new RegisterRequest("rachel", "rachel@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("rachel@example.com", "Correct-Horse1!");
        Cookie refreshCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        assertThat(refreshCookie).isNotNull();

        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk());
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        RegisterRequest register = new RegisterRequest("wendy", "wendy@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        disableUser("wendy@example.com");

        LoginRequest login = new LoginRequest("wendy@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.detail").value("این حساب کاربری غیرفعال شده و امکان ورود با آن وجود ندارد. در صورت وجود ابهام، با پشتیبانی سامانه تماس بگیرید"));
    }

    @Test
    void disabledUserExistingRefreshTokenIsRejectedAndRevoked() throws Exception {
        RegisterRequest register = new RegisterRequest("xena", "xena@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("xena@example.com", "Correct-Horse1!");
        Cookie refreshCookie = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        assertThat(refreshCookie).isNotNull();

        disableUser("xena@example.com");

        Cookie csrfCookie = fetchCsrfCookie();

        // Rejected once the account is disabled, even though the refresh token itself is
        // still otherwise valid (unexpired, unrevoked, unused) — and with a clear
        // account-disabled message rather than a generic invalid-token one.
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.detail").value("این حساب کاربری غیرفعال شده و امکان ورود با آن وجود ندارد. در صورت وجود ابهام، با پشتیبانی سامانه تماس بگیرید"));

        // The rejected token is also revoked as a side effect, not merely left unusable
        // because the account is disabled — a subsequent attempt with the same token fails
        // for the same reason a reused rotated token would.
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isUnauthorized());
    }

    private void disableUser(String email) {
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);
    }

    private Cookie fetchCsrfCookie() throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        return csrfCookie;
    }

    // SecurityConfig wires the CSRF token repository directly rather than via the .spa()
    // DSL shortcut, so CsrfFilter falls back to its default XorCsrfTokenRequestAttributeHandler,
    // which BREACH-masks the header value against the raw cookie token — a plain resend of the
    // cookie value as the header is rejected. Masked form is base64url(random || (random XOR token)).
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
}
