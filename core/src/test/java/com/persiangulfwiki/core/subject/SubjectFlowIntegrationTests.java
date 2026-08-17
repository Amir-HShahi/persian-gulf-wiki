package com.persiangulfwiki.core.subject;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.subject.dto.CreateSubjectRequest;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubjectFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private void registerContributor(String username, String email) throws Exception {
        RegisterRequest register = new RegisterRequest(username, email, PASSWORD);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());
    }

    // EmailVerificationRequiredFilter blocks an unverified session from everything outside a
    // small allowlist, so any account that has to actually reach these endpoints is verified
    // here first.
    private User registerVerifiedContributor(String username, String email) throws Exception {
        registerContributor(username, email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private User seedModerator(String username, String email) throws Exception {
        User user = registerVerifiedContributor(username, email);
        userRoleRepository.save(UserRole.builder().user(user).role(Role.MODERATOR).build());
        return user;
    }

    private Cookie loginAccessCookie(String email) throws Exception {
        LoginRequest login = new LoginRequest(email, PASSWORD);
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

    // The main smoke check: a moderator creates an island with a real point geometry, and the
    // geometry survives a round trip through PostGIS and comes back readable — by an entirely
    // anonymous caller, which is what proves the public-read rule is actually in effect rather
    // than the request merely reusing the creator's session.
    @Test
    void moderatorCreatesIslandAndAnonymousReadsItBack() throws Exception {
        seedModerator("sfmod1", "sf-mod1@example.com");
        Cookie accessCookie = loginAccessCookie("sf-mod1@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSubjectRequest request = new CreateSubjectRequest(
                SubjectKind.ISLAND, new BigDecimal("12.5000"), "POINT(52.1 26.4)", null, null);

        String created = mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("ISLAND"))
                .andExpect(jsonPath("$.data.areaKm2").value(12.5))
                .andExpect(jsonPath("$.data.location", startsWith("POINT")))
                .andReturn().getResponse().getContentAsString();

        UUID subjectId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.data.id"));

        // No cookie at all — the read must work for a caller with no account.
        mockMvc.perform(get("/api/subjects/" + subjectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("ISLAND"))
                .andExpect(jsonPath("$.data.areaKm2").value(12.5))
                .andExpect(jsonPath("$.data.location", containsString("52.1")))
                .andExpect(jsonPath("$.data.location", containsString("26.4")));
    }

    @Test
    void moderatorCreatesOilFieldWithPolygonAndReadsItBack() throws Exception {
        seedModerator("sfmod2", "sf-mod2@example.com");
        Cookie accessCookie = loginAccessCookie("sf-mod2@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSubjectRequest request = new CreateSubjectRequest(SubjectKind.OIL_FIELD, null, null,
                "POLYGON((50 26, 51 26, 51 27, 50 27, 50 26))", null);

        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("OIL_FIELD"))
                .andExpect(jsonPath("$.data.area", startsWith("POLYGON")))
                .andExpect(jsonPath("$.data.location").doesNotExist());
    }

    @Test
    void anonymousCannotCreateSubject() throws Exception {
        CreateSubjectRequest request = new CreateSubjectRequest(SubjectKind.PORT, null, null, null, null);
        mockMvc.perform(post("/api/subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void contributorWithoutModeratorRoleCannotCreateSubject() throws Exception {
        registerVerifiedContributor("sfalice", "sf-alice@example.com");
        Cookie accessCookie = loginAccessCookie("sf-alice@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSubjectRequest request = new CreateSubjectRequest(SubjectKind.PORT, null, null, null, null);
        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createWithoutCsrfTokenIsRejected() throws Exception {
        seedModerator("sfmod3", "sf-mod3@example.com");
        Cookie accessCookie = loginAccessCookie("sf-mod3@example.com");

        CreateSubjectRequest request = new CreateSubjectRequest(SubjectKind.PORT, null, null, null, null);
        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void fieldBelongingToAnotherKindIsRejected() throws Exception {
        seedModerator("sfmod4", "sf-mod4@example.com");
        Cookie accessCookie = loginAccessCookie("sf-mod4@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        // habitat belongs to SPECIES, not ISLAND — the value must be refused, not dropped.
        CreateSubjectRequest request = new CreateSubjectRequest(SubjectKind.ISLAND, null, null, null,
                "POLYGON((50 26, 51 26, 51 27, 50 27, 50 26))");

        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUBJECT_KIND_MISMATCH"));
    }

    @Test
    void malformedGeometryIsRejected() throws Exception {
        seedModerator("sfmod5", "sf-mod5@example.com");
        Cookie accessCookie = loginAccessCookie("sf-mod5@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSubjectRequest request = new CreateSubjectRequest(
                SubjectKind.PORT, null, "not a geometry at all", null, null);

        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GEOMETRY"));
    }

    // Structurally valid WKT, wrong geometry type for the field. Without the explicit type
    // check this would reach the database and fail as a 500 on the column's type constraint.
    @Test
    void geometryOfTheWrongTypeForTheFieldIsRejected() throws Exception {
        seedModerator("sfmod6", "sf-mod6@example.com");
        Cookie accessCookie = loginAccessCookie("sf-mod6@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSubjectRequest request = new CreateSubjectRequest(SubjectKind.PORT, null,
                "POLYGON((50 26, 51 26, 51 27, 50 27, 50 26))", null, null);

        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GEOMETRY"));
    }

    @Test
    void unknownSubjectIsNotFound() throws Exception {
        mockMvc.perform(get("/api/subjects/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    @Test
    void unknownKindFilterIsRejected() throws Exception {
        mockMvc.perform(get("/api/subjects").param("kind", "SEA_MONSTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUBJECT_KIND"));
    }

    @Test
    void listFilteredByKindReturnsOnlyThatKind() throws Exception {
        seedModerator("sfmod7", "sf-mod7@example.com");
        Cookie accessCookie = loginAccessCookie("sf-mod7@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSubjectRequest species = new CreateSubjectRequest(SubjectKind.SPECIES, null, null, null, null);
        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(species)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/subjects").param("kind", "species").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].kind").value("SPECIES"));
    }

    @Test
    void pageSizeAboveTheMaximumIsRejected() throws Exception {
        mockMvc.perform(get("/api/subjects").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // --- Regression coverage for what this change could plausibly have broken ---

    // The public-read rule names GET explicitly. If it were ever widened to the whole path,
    // this would start returning 201 instead of 403.
    @Test
    void openingPublicReadsDidNotOpenAnyOtherProtectedRoute() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/expert-reviewer/applications"))
                .andExpect(status().isUnauthorized());
    }

    // Five handlers were added to the shared error advice. This proves an existing, unrelated
    // one still maps to its own status and code rather than being shadowed by them.
    @Test
    void existingErrorHandlersStillMapToTheirOwnCodes() throws Exception {
        LoginRequest badLogin = new LoginRequest("sf-nobody@example.com", "Wrong-Horse1!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // The per-field validation breakdown is shared by every endpoint; adding handlers above it
    // must not have displaced it.
    @Test
    void missingKindStillProducesThePerFieldValidationBreakdown() throws Exception {
        seedModerator("sfmod8", "sf-mod8@example.com");
        Cookie accessCookie = loginAccessCookie("sf-mod8@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        mockMvc.perform(post("/api/subjects")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("kind"));
    }
}
