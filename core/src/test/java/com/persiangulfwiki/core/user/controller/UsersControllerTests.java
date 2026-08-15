package com.persiangulfwiki.core.user.controller;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.security.JwtService;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.model.RefreshToken;
import com.persiangulfwiki.core.user.repository.RefreshTokenRepository;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UsersControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @Test
    void meWithoutCookieReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithValidCookieReturnsProfile() throws Exception {
        User user = User.builder()
                .username("profile-user")
                .email("profile-user@example.com")
                .passwordHash("irrelevant-hash")
                .build();
        user = userRepository.save(user);

        UserRole role = UserRole.builder()
                .user(user)
                .role(Role.CONTRIBUTOR)
                .build();
        userRoleRepository.save(role);

        String token = jwtService.generateAccessToken(user.getId(), List.of(Role.CONTRIBUTOR), true);

        mockMvc.perform(get("/api/users/me").cookie(new Cookie("access_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.data.username").value("profile-user"))
                .andExpect(jsonPath("$.data.email").value("profile-user@example.com"))
                .andExpect(jsonPath("$.data.roles[0]").value("CONTRIBUTOR"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void sessionsListsOnlyActiveNonExpiredSessionsForCurrentUser() throws Exception {
        User user = userRepository.save(User.builder()
                .username("sessions-user")
                .email("sessions-user@example.com")
                .passwordHash("irrelevant-hash")
                .build());
        userRoleRepository.save(UserRole.builder().user(user).role(Role.CONTRIBUTOR).build());

        RefreshToken active = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash("hash-active-" + UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .deviceLabel("Chrome on macOS")
                .ipAddress("10.0.0.1")
                .build());
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash("hash-revoked-" + UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .revokedAt(Instant.now())
                .deviceLabel("Firefox on Linux")
                .ipAddress("10.0.0.2")
                .build());
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash("hash-expired-" + UUID.randomUUID())
                .expiresAt(Instant.now().minusSeconds(3600))
                .deviceLabel("Safari on iOS")
                .ipAddress("10.0.0.3")
                .build());

        String token = jwtService.generateAccessToken(user.getId(), List.of(Role.CONTRIBUTOR), true);

        mockMvc.perform(get("/api/users/me/sessions").cookie(new Cookie("access_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(active.getId().toString()))
                .andExpect(jsonPath("$.data[0].deviceLabel").value("Chrome on macOS"))
                .andExpect(jsonPath("$.data[0].ipAddress").value("10.0.0.1"))
                .andExpect(jsonPath("$.data[0].createdAt").exists())
                .andExpect(jsonPath("$.data[0].expiresAt").exists())
                .andExpect(jsonPath("$.data[0].tokenHash").doesNotExist());
    }

    @Test
    void revokeSessionRevokesOwnSessionSoSubsequentRefreshFails() throws Exception {
        RegisterRequest register = new RegisterRequest("laura", "laura@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("laura@example.com", "Correct-Horse1!");
        var loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Cookie accessCookie = loginResponse.getCookie("access_token");
        Cookie refreshCookie = loginResponse.getCookie("refresh_token");
        assertThat(accessCookie).isNotNull();
        assertThat(refreshCookie).isNotNull();

        User user = userRepository.findByEmail("laura@example.com").orElseThrow();
        UUID sessionId = refreshTokenRepository
                .findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(user.getId())
                .get(0).getId();

        // Registration leaves the account unverified, which the access token cookie above
        // still carries — mint a verified token directly rather than round-tripping through
        // email verification, since that's not what this test is exercising.
        String verifiedAccessToken = jwtService.generateAccessToken(user.getId(), List.of(Role.CONTRIBUTOR), true);
        accessCookie = new Cookie("access_token", verifiedAccessToken);

        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(delete("/api/users/me/sessions/{id}", sessionId)
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeSessionForAnotherUserReturnsNotFound() throws Exception {
        User userA = userRepository.save(User.builder()
                .username("user-a")
                .email("user-a@example.com")
                .passwordHash("irrelevant-hash")
                .build());
        User userB = userRepository.save(User.builder()
                .username("user-b")
                .email("user-b@example.com")
                .passwordHash("irrelevant-hash")
                .build());

        RefreshToken userBSession = refreshTokenRepository.save(RefreshToken.builder()
                .user(userB)
                .tokenHash("hash-user-b-" + UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());

        String accessToken = jwtService.generateAccessToken(userA.getId(), List.of(Role.CONTRIBUTOR), true);
        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(delete("/api/users/me/sessions/{id}", userBSession.getId())
                        .cookie(new Cookie("access_token", accessToken), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokeAlreadyRevokedSessionReturnsConflict() throws Exception {
        User user = userRepository.save(User.builder()
                .username("revoked-session-user")
                .email("revoked-session-user@example.com")
                .passwordHash("irrelevant-hash")
                .build());

        RefreshToken session = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash("hash-already-revoked-" + UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .revokedAt(Instant.now())
                .build());

        String accessToken = jwtService.generateAccessToken(user.getId(), List.of(Role.CONTRIBUTOR), true);
        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(delete("/api/users/me/sessions/{id}", session.getId())
                        .cookie(new Cookie("access_token", accessToken), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isConflict());
    }

    @Test
    void revokeSessionWithoutCsrfTokenIsRejected() throws Exception {
        User user = userRepository.save(User.builder()
                .username("no-csrf-user")
                .email("no-csrf-user@example.com")
                .passwordHash("irrelevant-hash")
                .build());

        RefreshToken session = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash("hash-no-csrf-" + UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());

        String accessToken = jwtService.generateAccessToken(user.getId(), List.of(Role.CONTRIBUTOR), true);

        mockMvc.perform(delete("/api/users/me/sessions/{id}", session.getId())
                        .cookie(new Cookie("access_token", accessToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlinkGoogleClearsColumnAndSendsNotification() throws Exception {
        User user = userRepository.save(User.builder()
                .username("google-linked-user")
                .email("google-linked-user@example.com")
                .passwordHash("irrelevant-hash")
                .googleSub("google-sub-" + UUID.randomUUID())
                .build());

        String accessToken = jwtService.generateAccessToken(user.getId(), List.of(Role.CONTRIBUTOR), true);
        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(delete("/api/users/me/oauth/google")
                        .cookie(new Cookie("access_token", accessToken), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk());

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getGoogleSub()).isNull();

        verify(javaMailSender, timeout(2000).times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void unlinkGoogleOnAlreadyUnlinkedAccountIsIdempotentAndSendsNoNotification() throws Exception {
        User user = userRepository.save(User.builder()
                .username("google-unlinked-user")
                .email("google-unlinked-user@example.com")
                .passwordHash("irrelevant-hash")
                .build());

        String accessToken = jwtService.generateAccessToken(user.getId(), List.of(Role.CONTRIBUTOR), true);
        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(delete("/api/users/me/oauth/google")
                        .cookie(new Cookie("access_token", accessToken), csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue())))
                .andExpect(status().isOk());

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getGoogleSub()).isNull();

        verify(javaMailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void unlinkGoogleWithoutCsrfTokenIsRejected() throws Exception {
        User user = userRepository.save(User.builder()
                .username("google-no-csrf-user")
                .email("google-no-csrf-user@example.com")
                .passwordHash("irrelevant-hash")
                .googleSub("google-sub-" + UUID.randomUUID())
                .build());

        String accessToken = jwtService.generateAccessToken(user.getId(), List.of(Role.CONTRIBUTOR), true);

        mockMvc.perform(delete("/api/users/me/oauth/google")
                        .cookie(new Cookie("access_token", accessToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlinkGoogleWithoutCookieReturnsUnauthorized() throws Exception {
        // CSRF is checked before authentication, so a valid CSRF token must be supplied here
        // to isolate the auth-cookie check rather than getting a 403 from the CSRF filter first.
        Cookie csrfCookie = fetchCsrfCookie();
        mockMvc.perform(delete("/api/users/me/oauth/google")
                        .cookie(csrfCookie)
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
