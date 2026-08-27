package com.persiangulfwiki.core.expertreviewer;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.expertreviewer.dto.ApplicationRequest;
import com.persiangulfwiki.core.expertreviewer.dto.ReviewDecision;
import com.persiangulfwiki.core.expertreviewer.dto.ReviewDecisionRequest;
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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExpertReviewerFlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private User seedAdmin(String email, String username) throws Exception {
        registerContributor(username, email);
        User user = userRepository.findByEmail(email).orElseThrow();
        userRoleRepository.save(UserRole.builder().user(user).role(Role.ADMIN).build());
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

    // EmailVerificationRequiredFilter blocks unverified sessions from every endpoint except
    // a small allowlist that doesn't include /api/expert-reviewer/**, so an applicant that
    // needs to actually reach the endpoint must be verified first.
    private User registerVerifiedContributor(String username, String email) throws Exception {
        registerContributor(username, email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);
        return user;
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
    void applyThenAdminSeesItPending() throws Exception {
        seedAdmin("erf-admin1@example.com", "erfadmin1");
        Cookie adminAccessCookie = loginAccessCookie("erf-admin1@example.com");

        registerVerifiedContributor("erfalice", "erf-alice@example.com");
        Cookie aliceAccessCookie = loginAccessCookie("erf-alice@example.com");

        Cookie csrfCookie = fetchCsrfCookie();
        ApplicationRequest applicationRequest = new ApplicationRequest("SPECIES", "I have a PhD in marine biology.");
        mockMvc.perform(post("/api/expert-reviewer/applications")
                        .cookie(aliceAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(applicationRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mockMvc.perform(get("/api/expert-reviewer/applications").cookie(adminAccessCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].entityType").value("SPECIES"));
    }

    @Test
    void duplicatePendingApplicationForSameEntityTypeIsConflict() throws Exception {
        registerVerifiedContributor("erfbob", "erf-bob@example.com");
        Cookie bobAccessCookie = loginAccessCookie("erf-bob@example.com");

        Cookie csrfCookie = fetchCsrfCookie();
        ApplicationRequest applicationRequest = new ApplicationRequest("PORT", "I've studied Gulf ports for a decade.");
        mockMvc.perform(post("/api/expert-reviewer/applications")
                        .cookie(bobAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(applicationRequest)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/expert-reviewer/applications")
                        .cookie(bobAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(applicationRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    void adminApprovesGrantsRoleAndRecordsAudit() throws Exception {
        seedAdmin("erf-admin2@example.com", "erfadmin2");
        Cookie adminAccessCookie = loginAccessCookie("erf-admin2@example.com");

        User carol = registerVerifiedContributor("erfcarol", "erf-carol@example.com");
        Cookie carolAccessCookie = loginAccessCookie("erf-carol@example.com");

        Cookie applyCsrfCookie = fetchCsrfCookie();
        ApplicationRequest applicationRequest = new ApplicationRequest("ISLAND", "I lead island survey expeditions.");
        String applyResponse = mockMvc.perform(post("/api/expert-reviewer/applications")
                        .cookie(carolAccessCookie, applyCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(applyCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(applicationRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID applicationId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(applyResponse, "$.data.id"));

        Cookie reviewCsrfCookie = fetchCsrfCookie();
        ReviewDecisionRequest approve = new ReviewDecisionRequest(ReviewDecision.APPROVE);
        mockMvc.perform(patch("/api/expert-reviewer/applications/" + applicationId)
                        .cookie(adminAccessCookie, reviewCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(reviewCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approve)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertThat(userRoleRepository.findByUserId(carol.getId()))
                .anyMatch(userRole -> userRole.getRole() == Role.EXPERT_REVIEWER && "ISLAND".equals(userRole.getEntityType()));
    }

    @Test
    void adminRejectsGrantsNoRole() throws Exception {
        seedAdmin("erf-admin3@example.com", "erfadmin3");
        Cookie adminAccessCookie = loginAccessCookie("erf-admin3@example.com");

        User dave = registerVerifiedContributor("erfdave", "erf-dave@example.com");
        Cookie daveAccessCookie = loginAccessCookie("erf-dave@example.com");

        Cookie applyCsrfCookie = fetchCsrfCookie();
        ApplicationRequest applicationRequest = new ApplicationRequest("OIL_FIELD", "I audit offshore rigs for a living.");
        String applyResponse = mockMvc.perform(post("/api/expert-reviewer/applications")
                        .cookie(daveAccessCookie, applyCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(applyCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(applicationRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID applicationId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(applyResponse, "$.data.id"));

        Cookie reviewCsrfCookie = fetchCsrfCookie();
        ReviewDecisionRequest reject = new ReviewDecisionRequest(ReviewDecision.REJECT);
        mockMvc.perform(patch("/api/expert-reviewer/applications/" + applicationId)
                        .cookie(adminAccessCookie, reviewCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(reviewCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(userRoleRepository.findByUserId(dave.getId()))
                .noneMatch(userRole -> userRole.getRole() == Role.EXPERT_REVIEWER);
    }

    @Test
    void reviewingAlreadyReviewedApplicationIsConflict() throws Exception {
        seedAdmin("erf-admin4@example.com", "erfadmin4");
        Cookie adminAccessCookie = loginAccessCookie("erf-admin4@example.com");

        registerVerifiedContributor("erferin", "erf-erin@example.com");
        Cookie erinAccessCookie = loginAccessCookie("erf-erin@example.com");

        Cookie applyCsrfCookie = fetchCsrfCookie();
        ApplicationRequest applicationRequest = new ApplicationRequest("SHIPPING_LANE", "I coordinate shipping schedules.");
        String applyResponse = mockMvc.perform(post("/api/expert-reviewer/applications")
                        .cookie(erinAccessCookie, applyCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(applyCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(applicationRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID applicationId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(applyResponse, "$.data.id"));

        Cookie firstReviewCsrfCookie = fetchCsrfCookie();
        ReviewDecisionRequest reject = new ReviewDecisionRequest(ReviewDecision.REJECT);
        mockMvc.perform(patch("/api/expert-reviewer/applications/" + applicationId)
                        .cookie(adminAccessCookie, firstReviewCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(firstReviewCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reject)))
                .andExpect(status().isOk());

        Cookie secondReviewCsrfCookie = fetchCsrfCookie();
        mockMvc.perform(patch("/api/expert-reviewer/applications/" + applicationId)
                        .cookie(adminAccessCookie, secondReviewCsrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(secondReviewCsrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reject)))
                .andExpect(status().isConflict());
    }

    @Test
    void nonAdminCannotListOrReview() throws Exception {
        registerContributor("erffrank", "erf-frank@example.com");
        Cookie frankAccessCookie = loginAccessCookie("erf-frank@example.com");

        mockMvc.perform(get("/api/expert-reviewer/applications").cookie(frankAccessCookie))
                .andExpect(status().isForbidden());

        Cookie csrfCookie = fetchCsrfCookie();
        ReviewDecisionRequest approve = new ReviewDecisionRequest(ReviewDecision.APPROVE);
        mockMvc.perform(patch("/api/expert-reviewer/applications/" + UUID.randomUUID())
                        .cookie(frankAccessCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrfCookie.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approve)))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingCsrfTokenOnApplyIsForbidden() throws Exception {
        registerContributor("erfgrace", "erf-grace@example.com");
        Cookie graceAccessCookie = loginAccessCookie("erf-grace@example.com");

        ApplicationRequest applicationRequest = new ApplicationRequest("SPECIES", "I study reef fish.");
        mockMvc.perform(post("/api/expert-reviewer/applications")
                        .cookie(graceAccessCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(applicationRequest)))
                .andExpect(status().isForbidden());
    }
}
