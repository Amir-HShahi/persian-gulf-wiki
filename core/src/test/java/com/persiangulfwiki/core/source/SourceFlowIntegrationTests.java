package com.persiangulfwiki.core.source;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.source.dto.CreateSourceRequest;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
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
class SourceFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private User registerVerifiedContributor(String username, String email) throws Exception {
        RegisterRequest register = new RegisterRequest(username, email, PASSWORD);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        return userRepository.save(user);
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

    // Smoke check: an ordinary verified contributor — no moderator role — creates a citation,
    // the creating account is recorded on it, and an anonymous caller can read it back.
    @Test
    void contributorCreatesSourceAndAnonymousReadsItBack() throws Exception {
        User bob = registerVerifiedContributor("srcbob", "src-bob@example.com");
        Cookie accessCookie = loginAccessCookie("src-bob@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSourceRequest request = new CreateSourceRequest(
                "Gulf Island Survey 1998", "https://example.org/survey-1998",
                "Regional Hydrographic Office", LocalDate.of(1998, 6, 1));

        String created = mockMvc.perform(post("/api/sources")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("Gulf Island Survey 1998"))
                .andExpect(jsonPath("$.data.createdByUserId").value(bob.getId().toString()))
                .andReturn().getResponse().getContentAsString();

        UUID sourceId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.data.id"));

        mockMvc.perform(get("/api/sources/" + sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publisher").value("Regional Hydrographic Office"))
                .andExpect(jsonPath("$.data.publishedOn").value("1998-06-01"));
    }

    // A citation taken from a physical document routinely has nothing but a title.
    @Test
    void titleAloneIsEnoughToCreateASource() throws Exception {
        registerVerifiedContributor("srccarol", "src-carol@example.com");
        Cookie accessCookie = loginAccessCookie("src-carol@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSourceRequest request = new CreateSourceRequest("An uncatalogued field notebook", null, null, null);

        mockMvc.perform(post("/api/sources")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.url").doesNotExist())
                .andExpect(jsonPath("$.data.publishedOn").doesNotExist());
    }

    @Test
    void anonymousCannotCreateSource() throws Exception {
        CreateSourceRequest request = new CreateSourceRequest("Anonymous submission", null, null, null);
        mockMvc.perform(post("/api/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createWithoutCsrfTokenIsRejected() throws Exception {
        registerVerifiedContributor("srcdave", "src-dave@example.com");
        Cookie accessCookie = loginAccessCookie("src-dave@example.com");

        CreateSourceRequest request = new CreateSourceRequest("No CSRF token", null, null, null);
        mockMvc.perform(post("/api/sources")
                        .cookie(accessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankTitleIsRejected() throws Exception {
        registerVerifiedContributor("srcerin", "src-erin@example.com");
        Cookie accessCookie = loginAccessCookie("src-erin@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSourceRequest request = new CreateSourceRequest("   ", null, null, null);
        mockMvc.perform(post("/api/sources")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void publicationDateInTheFutureIsRejected() throws Exception {
        registerVerifiedContributor("srcfrank", "src-frank@example.com");
        Cookie accessCookie = loginAccessCookie("src-frank@example.com");
        Cookie csrfCookie = fetchCsrfCookie();

        CreateSourceRequest request = new CreateSourceRequest(
                "Tomorrow's almanac", null, null, LocalDate.now().plusYears(1));

        mockMvc.perform(post("/api/sources")
                        .cookie(accessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("publishedOn"));
    }

    @Test
    void unknownSourceIsNotFound() throws Exception {
        mockMvc.perform(get("/api/sources/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
    }

    @Test
    void malformedSourceIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/sources/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
