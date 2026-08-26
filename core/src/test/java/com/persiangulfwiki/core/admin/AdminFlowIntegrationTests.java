package com.persiangulfwiki.core.admin;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.admin.dto.GrantAction;
import com.persiangulfwiki.core.admin.dto.RoleGrantRequest;
import com.persiangulfwiki.core.admin.dto.UserStatusAction;
import com.persiangulfwiki.core.admin.dto.UserStatusRequest;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminFlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    // No ADMIN_BOOTSTRAP_* env vars are set for this test context, so no admin exists yet —
    // seed one directly via the repositories, the same way AuthFlowIntegrationTests seeds a
    // disabled user, rather than depending on AdminBootstrapRunner (covered separately by
    // AdminBootstrapIntegrationTests).
    private User seedAdmin(String email, String username) throws Exception {
        registerContributor(username, email);
        User user = userRepository.findByEmail(email).orElseThrow();
        userRoleRepository.save(UserRole.builder().user(user).role(Role.ADMIN).build());

        // EmailVerificationRequiredFilter blocks unverified sessions from every endpoint
        // except a small allowlist that doesn't include /api/admin/**, so the acting admin's
        // own account needs emailVerified=true or every admin call in this test would 403
        // before ever reaching the @PreAuthorize check.
        user.setEmailVerified(true);
        userRepository.save(user);
        return user;
    }

    private void registerContributor(String username, String email) throws Exception {
        RegisterRequest register = new RegisterRequest(username, email, "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
    }

    private Cookie loginAccessCookie(String email) throws Exception {
        LoginRequest login = new LoginRequest(email, "Correct-Horse1!");
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private Cookie fetchCsrfCookie() throws Exception {
        Cookie csrfCookie = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        return csrfCookie;
    }

    // Mirrors AuthFlowIntegrationTests.maskCsrfToken: SecurityConfig wires the CSRF token
    // repository directly rather than via .spa(), so the default
    // XorCsrfTokenRequestAttributeHandler BREACH-masks the header value against the raw
    // cookie token.
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
    void nonAdminHittingAnyAdminEndpointIsForbidden() throws Exception {
        registerContributor("afalice", "af-alice@example.com");
        Cookie accessCookie = loginAccessCookie("af-alice@example.com");

        mockMvc.perform(get("/api/admin/users").cookie(accessCookie))
                .andExpect(status().isForbidden());

        Cookie csrfCookie = fetchCsrfCookie();
        RoleGrantRequest grant = new RoleGrantRequest(Role.MODERATOR, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + java.util.UUID.randomUUID() + "/role")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grant)))
                .andExpect(status().isForbidden());

        UserStatusRequest suspend = new UserStatusRequest(UserStatusAction.SUSPEND);
        mockMvc.perform(patch("/api/admin/users/" + java.util.UUID.randomUUID() + "/status")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(suspend)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminGrantsModeratorAndGrantedUserSubsequentLoginReflectsIt() throws Exception {
        seedAdmin("admin1@example.com", "admin1");
        Cookie adminAccessCookie = loginAccessCookie("admin1@example.com");

        registerContributor("afbob", "af-bob@example.com");
        User bob = userRepository.findByEmail("af-bob@example.com").orElseThrow();

        Cookie csrfCookie = fetchCsrfCookie();
        RoleGrantRequest grant = new RoleGrantRequest(Role.MODERATOR, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + bob.getId() + "/role")
                        .cookie(adminAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grant)))
                .andExpect(status().isOk());

        Cookie bobAccessCookie = loginAccessCookie("af-bob@example.com");
        mockMvc.perform(get("/api/users/me").cookie(bobAccessCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", org.hamcrest.Matchers.hasItem("MODERATOR")));
    }

    @Test
    void grantingSameRoleTwiceIsConflict() throws Exception {
        seedAdmin("admin2@example.com", "admin2");
        Cookie adminAccessCookie = loginAccessCookie("admin2@example.com");

        registerContributor("afcarol", "af-carol@example.com");
        User carol = userRepository.findByEmail("af-carol@example.com").orElseThrow();

        Cookie csrfCookie = fetchCsrfCookie();
        RoleGrantRequest grant = new RoleGrantRequest(Role.MODERATOR, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + carol.getId() + "/role")
                        .cookie(adminAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grant)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/users/" + carol.getId() + "/role")
                        .cookie(adminAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grant)))
                .andExpect(status().isConflict());
    }

    @Test
    void soleAdminCannotRevokeTheirOwnAdminRole() throws Exception {
        User admin = seedAdmin("admin3@example.com", "admin3");

        // Other tests in this class each seed their own admin against the same
        // Testcontainers Postgres instance (no @Transactional rollback between methods, and
        // JUnit doesn't guarantee execution order), so "sole admin" has to be enforced
        // explicitly here rather than assumed from this test's own setup alone.
        userRoleRepository.findAll().stream()
                .filter(userRole -> userRole.getRole() == Role.ADMIN && !userRole.getUser().getId().equals(admin.getId()))
                .forEach(userRoleRepository::delete);
        assertThat(userRoleRepository.countByRole(Role.ADMIN)).isEqualTo(1L);

        Cookie adminAccessCookie = loginAccessCookie("admin3@example.com");

        Cookie csrfCookie = fetchCsrfCookie();
        RoleGrantRequest revoke = new RoleGrantRequest(Role.ADMIN, null, GrantAction.REVOKE);
        mockMvc.perform(patch("/api/admin/users/" + admin.getId() + "/role")
                        .cookie(adminAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(revoke)))
                .andExpect(status().isConflict());

        assertThat(userRoleRepository.findByUserId(admin.getId()))
                .anyMatch(userRole -> userRole.getRole() == Role.ADMIN);
    }

    @Test
    void adminSuspendsUserAndThatUsersLoginIsThenRejected() throws Exception {
        seedAdmin("admin4@example.com", "admin4");
        Cookie adminAccessCookie = loginAccessCookie("admin4@example.com");

        registerContributor("afdave", "af-dave@example.com");
        User dave = userRepository.findByEmail("af-dave@example.com").orElseThrow();

        Cookie csrfCookie = fetchCsrfCookie();
        UserStatusRequest suspend = new UserStatusRequest(UserStatusAction.SUSPEND);
        mockMvc.perform(patch("/api/admin/users/" + dave.getId() + "/status")
                        .cookie(adminAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(suspend)))
                .andExpect(status().isOk());

        LoginRequest login = new LoginRequest("af-dave@example.com", "Correct-Horse1!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    // RoleHierarchy interaction edge cases: multi-role users against
    // ROLE_ADMIN > ROLE_MODERATOR. Each test below issues a fresh access token (login or
    // refresh) after any role change, since JwtAuthenticationFilter builds authorities
    // solely from the token's own role claim — the hierarchy is expanded live by
    // DefaultMethodSecurityExpressionHandler at @PreAuthorize evaluation time from whatever
    // authorities that token carries, never baked into the token itself.

    @Test
    void moderatorOnlyUserCannotReachAdminOnlyEndpoint() throws Exception {
        seedAdmin("admin6@example.com", "admin6");
        Cookie adminAccessCookie = loginAccessCookie("admin6@example.com");

        registerContributor("affrank", "af-frank@example.com");
        User frank = userRepository.findByEmail("af-frank@example.com").orElseThrow();

        Cookie csrfCookie = fetchCsrfCookie();
        RoleGrantRequest grantModerator = new RoleGrantRequest(Role.MODERATOR, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + frank.getId() + "/role")
                        .cookie(adminAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantModerator)))
                .andExpect(status().isOk());

        // The hierarchy is directional (ADMIN > MODERATOR means ADMIN implies MODERATOR,
        // never the reverse) — a MODERATOR-only user must still be rejected here, confirming
        // it wasn't accidentally configured backwards.
        Cookie frankAccessCookie = loginAccessCookie("af-frank@example.com");
        Cookie frankCsrfCookie = fetchCsrfCookie();
        RoleGrantRequest grantSomeoneElse = new RoleGrantRequest(Role.MODERATOR, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + java.util.UUID.randomUUID() + "/role")
                        .cookie(frankAccessCookie, frankCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(frankCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantSomeoneElse)))
                .andExpect(status().isForbidden());
    }

    @Test
    void moderatorPlusExpertReviewerWithoutAdminCannotReachAdminOnlyEndpoint() throws Exception {
        seedAdmin("admin7@example.com", "admin7");
        Cookie adminAccessCookie = loginAccessCookie("admin7@example.com");

        registerContributor("afgrace", "af-grace@example.com");
        User grace = userRepository.findByEmail("af-grace@example.com").orElseThrow();

        Cookie csrfCookie = fetchCsrfCookie();
        RoleGrantRequest grantModerator = new RoleGrantRequest(Role.MODERATOR, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + grace.getId() + "/role")
                        .cookie(adminAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantModerator)))
                .andExpect(status().isOk());

        Cookie csrfCookie2 = fetchCsrfCookie();
        RoleGrantRequest grantExpertReviewer = new RoleGrantRequest(Role.EXPERT_REVIEWER, "Species", GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + grace.getId() + "/role")
                        .cookie(adminAccessCookie, csrfCookie2)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie2.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantExpertReviewer)))
                .andExpect(status().isOk());

        // EXPERT_REVIEWER is deliberately absent from the hierarchy (RoleHierarchyConfig) —
        // combining it with MODERATOR must not accidentally fold it into the ADMIN/MODERATOR
        // ladder.
        Cookie graceAccessCookie = loginAccessCookie("af-grace@example.com");
        mockMvc.perform(get("/api/users/me").cookie(graceAccessCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", org.hamcrest.Matchers.hasItem("MODERATOR")))
                .andExpect(jsonPath("$.data.roles", org.hamcrest.Matchers.hasItem("EXPERT_REVIEWER")));

        Cookie graceCsrfCookie = fetchCsrfCookie();
        RoleGrantRequest grantSomeoneElse = new RoleGrantRequest(Role.MODERATOR, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + java.util.UUID.randomUUID() + "/role")
                        .cookie(graceAccessCookie, graceCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(graceCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantSomeoneElse)))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokedAdminsFreshlyIssuedTokenNoLongerPassesModeratorHierarchyCheck() throws Exception {
        seedAdmin("admin8@example.com", "admin8");
        Cookie superAdminCookie = loginAccessCookie("admin8@example.com");

        // Henry gets ADMIN directly (never separately granted MODERATOR), so once ADMIN is
        // revoked he has no role left to reach a MODERATOR-gated check through — only
        // through the (now inapplicable) hierarchy.
        registerContributor("afhenry", "af-henry@example.com");
        User henry = userRepository.findByEmail("af-henry@example.com").orElseThrow();
        // /api/admin/** is behind EmailVerificationRequiredFilter same as any other endpoint,
        // and this test's assertions are about the hierarchy, not email verification — so
        // verify Henry upfront, same as seedAdmin does for the acting admin.
        henry.setEmailVerified(true);
        userRepository.save(henry);

        Cookie csrfCookie = fetchCsrfCookie();
        RoleGrantRequest grantAdmin = new RoleGrantRequest(Role.ADMIN, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + henry.getId() + "/role")
                        .cookie(superAdminCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantAdmin)))
                .andExpect(status().isOk());

        Cookie henryAccessCookie = loginAccessCookie("af-henry@example.com");
        mockMvc.perform(get("/api/admin/users").cookie(henryAccessCookie))
                .andExpect(status().isOk());

        Cookie csrfCookie2 = fetchCsrfCookie();
        RoleGrantRequest revokeAdmin = new RoleGrantRequest(Role.ADMIN, null, GrantAction.REVOKE);
        mockMvc.perform(patch("/api/admin/users/" + henry.getId() + "/role")
                        .cookie(superAdminCookie, csrfCookie2)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie2.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(revokeAdmin)))
                .andExpect(status().isOk());

        // The access token Henry already holds still carries the old role claim and keeps
        // working until it expires (JwtAuthenticationFilter is stateless — see the comment
        // at AdminController's role endpoint), so asserting it fails here would test an
        // unrealistic instant-revocation model the JWT architecture doesn't provide. What's
        // actually guaranteed is that his NEXT issued token no longer grants access.
        Cookie henryFreshAccessCookie = loginAccessCookie("af-henry@example.com");

        mockMvc.perform(get("/api/admin/users").cookie(henryFreshAccessCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void grantedAdminsFreshlyIssuedTokenPassesBothAdminAndHierarchyDerivedModeratorChecks() throws Exception {
        seedAdmin("admin9@example.com", "admin9");
        Cookie superAdminCookie = loginAccessCookie("admin9@example.com");

        registerContributor("afiris", "af-iris@example.com");
        User iris = userRepository.findByEmail("af-iris@example.com").orElseThrow();
        iris.setEmailVerified(true);
        userRepository.save(iris);

        Cookie csrfCookie = fetchCsrfCookie();
        RoleGrantRequest grantAdmin = new RoleGrantRequest(Role.ADMIN, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + iris.getId() + "/role")
                        .cookie(superAdminCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantAdmin)))
                .andExpect(status().isOk());

        // Iris's next issued token must pass a direct hasRole('ADMIN') check (the role she
        // was actually granted) as well as a hasRole('MODERATOR') check she only reaches via
        // the hierarchy — the positive-direction mirror of the negative cases above.
        Cookie irisAccessCookie = loginAccessCookie("af-iris@example.com");
        mockMvc.perform(get("/api/admin/users").cookie(irisAccessCookie))
                .andExpect(status().isOk());

        Cookie irisCsrfCookie = fetchCsrfCookie();
        RoleGrantRequest grantSomeoneElse = new RoleGrantRequest(Role.MODERATOR, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + java.util.UUID.randomUUID() + "/role")
                        .cookie(irisAccessCookie, irisCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(irisCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantSomeoneElse)))
                .andExpect(status().isNotFound());
    }

    @Test
    void mutatingAdminEndpointsWithoutCsrfTokenAreForbidden() throws Exception {
        User admin = seedAdmin("admin5@example.com", "admin5");
        Cookie adminAccessCookie = loginAccessCookie("admin5@example.com");

        registerContributor("aferin", "af-erin@example.com");
        User erin = userRepository.findByEmail("af-erin@example.com").orElseThrow();

        RoleGrantRequest grant = new RoleGrantRequest(Role.MODERATOR, null, GrantAction.GRANT);
        mockMvc.perform(patch("/api/admin/users/" + erin.getId() + "/role")
                        .cookie(adminAccessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grant)))
                .andExpect(status().isForbidden());

        UserStatusRequest suspend = new UserStatusRequest(UserStatusAction.SUSPEND);
        mockMvc.perform(patch("/api/admin/users/" + erin.getId() + "/status")
                        .cookie(adminAccessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(suspend)))
                .andExpect(status().isForbidden());

        RoleGrantRequest revoke = new RoleGrantRequest(Role.ADMIN, null, GrantAction.REVOKE);
        mockMvc.perform(patch("/api/admin/users/" + admin.getId() + "/role")
                        .cookie(adminAccessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(revoke)))
                .andExpect(status().isForbidden());
    }
}
