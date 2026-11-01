package com.persiangulfwiki.core.moderation;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Drives real submit -> claim -> decide rounds, then reads the dashboard. Every count is
// asserted as a change from a reading taken just before, because moderation tables are shared
// with every other test in the suite; the exact arithmetic (deltas, rounding, gap filling) is
// pinned down in ModerationDashboardServiceTests instead.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ModerationDashboardFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Cookie authorAccess;
    private Cookie authorCsrf;
    private Cookie moderatorAccess;
    private Cookie moderatorCsrf;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String authorEmail = "dash-author-" + suffix + "@example.com";
        registerVerified("dashauthor" + suffix, authorEmail);
        authorAccess = loginAccessCookie(authorEmail);
        authorCsrf = fetchCsrfCookie();

        String moderatorEmail = "dash-mod-" + suffix + "@example.com";
        grantRole(registerVerified("dashmod" + suffix, moderatorEmail), Role.MODERATOR);
        moderatorAccess = loginAccessCookie(moderatorEmail);
        moderatorCsrf = fetchCsrfCookie();
    }

    // --- Account helpers (same dance as ModerationFlowIntegrationTests) ---

    private User registerVerified(String username, String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    // Before login: authorities are baked into the access token at login time.
    private void grantRole(User user, Role role) {
        userRoleRepository.save(UserRole.builder().user(user).role(role).build());
    }

    private Cookie loginAccessCookie(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private Cookie fetchCsrfCookie() throws Exception {
        return mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
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

    // --- Moderation helpers ---

    // Creates an article as the author, submits its first revision, and returns the task id.
    private UUID submitNewRevision() throws Exception {
        CreateArticleRequest create = new CreateArticleRequest(null, "fa", "dash-" + UUID.randomUUID(), "Title",
                objectMapper.readTree("{\"text\":\"body\"}"), null, null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(authorAccess, authorCsrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(authorCsrf.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String articleId = JsonPath.read(created, "$.data.id");
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions")
                        .cookie(authorAccess))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID revisionId = UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
        mockMvc.perform(post("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId + "/submit")
                        .cookie(authorAccess, authorCsrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(authorCsrf.getValue())))
                .andExpect(status().isOk());
        return moderationTaskRepository.findByRevisionId(revisionId).orElseThrow().getId();
    }

    private void claimAndDecide(UUID taskId, Decision decision) throws Exception {
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/claim")
                        .cookie(moderatorAccess, moderatorCsrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(moderatorCsrf.getValue())))
                .andExpect(status().isOk());
        String reason = decision == Decision.APPROVE ? null : "Needs a source.";
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/decide")
                        .cookie(moderatorAccess, moderatorCsrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(moderatorCsrf.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecideRequest(decision, reason))))
                .andExpect(status().isOk());
    }

    // created_at is updatable = false and stamped on insert, so moving a row to an earlier day
    // takes a direct statement.
    private void backdateTask(UUID taskId, Instant at) {
        jdbcTemplate.update("update moderation_tasks set created_at = ? where id = ?", Timestamp.from(at), taskId);
    }

    private void backdateDecisions(UUID taskId, Instant at) {
        jdbcTemplate.update("update moderation_decisions set created_at = ? where task_id = ?", Timestamp.from(at),
                taskId);
    }

    private static Instant noonDaysAgo(int days) {
        return LocalDate.now(TEHRAN).minusDays(days).atTime(12, 0).atZone(TEHRAN).toInstant();
    }

    private String summary() throws Exception {
        return mockMvc.perform(get("/api/moderation/dashboard/summary").cookie(moderatorAccess))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String articles(String range) throws Exception {
        return mockMvc.perform(get("/api/moderation/dashboard/articles").param("range", range).cookie(moderatorAccess))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static long count(String body, String path) {
        return ((Number) JsonPath.read(body, path)).longValue();
    }

    // --- Summary ---

    @Test
    void summaryCountsTodaysArticleSubmissionsAndDecisions() throws Exception {
        String before = summary();

        submitNewRevision();
        UUID sentBack = submitNewRevision();
        claimAndDecide(sentBack, Decision.REQUEST_CHANGES);
        UUID approved = submitNewRevision();
        claimAndDecide(approved, Decision.APPROVE);
        UUID rejected = submitNewRevision();
        claimAndDecide(rejected, Decision.REJECT);
        // Submitted and approved yesterday: in none of today's figures.
        UUID approvedYesterday = submitNewRevision();
        claimAndDecide(approvedYesterday, Decision.APPROVE);
        backdateTask(approvedYesterday, noonDaysAgo(1));
        backdateDecisions(approvedYesterday, noonDaysAgo(1));

        String after = summary();

        assertThat((String) JsonPath.read(after, "$.data.date")).isEqualTo(LocalDate.now(TEHRAN).toString());
        // Still waiting and sent back for changes both count; decided ones no longer do.
        assertThat(count(after, "$.data.pending.count") - count(before, "$.data.pending.count")).isEqualTo(2);
        assertThat(count(after, "$.data.approved.count") - count(before, "$.data.approved.count")).isEqualTo(1);
        assertThat(count(after, "$.data.rejected.count") - count(before, "$.data.rejected.count")).isEqualTo(1);
    }

    // --- Articles range ---

    @Test
    void articlesReturnsOnePointPerDayEndingTodayAndCountsBackdatedApprovalsOnTheirDay() throws Exception {
        LocalDate today = LocalDate.now(TEHRAN);
        String before = articles("LAST_7_DAYS");

        UUID approved = submitNewRevision();
        claimAndDecide(approved, Decision.APPROVE);
        backdateDecisions(approved, noonDaysAgo(3));

        String after = articles("LAST_7_DAYS");

        assertThat((String) JsonPath.read(after, "$.data.range")).isEqualTo("LAST_7_DAYS");
        assertThat((String) JsonPath.read(after, "$.data.from")).isEqualTo(today.minusDays(6).toString());
        assertThat((String) JsonPath.read(after, "$.data.to")).isEqualTo(today.toString());
        List<String> dates = JsonPath.read(after, "$.data.approvedPerDay[*].date");
        assertThat(dates).containsExactlyElementsOf(
                today.minusDays(6).datesUntil(today.plusDays(1)).map(LocalDate::toString).toList());
        // Index 3 is three days ago: 7 points, today last at index 6.
        assertThat(count(after, "$.data.approvedPerDay[3].count") - count(before, "$.data.approvedPerDay[3].count"))
                .isEqualTo(1);
        assertThat(count(after, "$.data.approvedPerDay[6].count")).isEqualTo(count(before, "$.data.approvedPerDay[6].count"));
    }

    @Test
    void articlesBreakdownGroupsRevisionsSubmittedInRangeByCurrentStatus() throws Exception {
        String before = articles("LAST_30_DAYS");

        submitNewRevision();
        UUID sentBack = submitNewRevision();
        claimAndDecide(sentBack, Decision.REQUEST_CHANGES);
        UUID approved = submitNewRevision();
        claimAndDecide(approved, Decision.APPROVE);
        UUID rejected = submitNewRevision();
        claimAndDecide(rejected, Decision.REJECT);
        // First submitted before the 30-day window: left out of the split.
        UUID tooOld = submitNewRevision();
        backdateTask(tooOld, noonDaysAgo(30));

        String after = articles("last_30_days");

        assertThat(JsonPath.<List<Object>>read(after, "$.data.approvedPerDay")).hasSize(30);
        assertThat(count(after, "$.data.statusBreakdown.pending.count")
                - count(before, "$.data.statusBreakdown.pending.count")).isEqualTo(2);
        assertThat(count(after, "$.data.statusBreakdown.approved.count")
                - count(before, "$.data.statusBreakdown.approved.count")).isEqualTo(1);
        assertThat(count(after, "$.data.statusBreakdown.rejected.count")
                - count(before, "$.data.statusBreakdown.rejected.count")).isEqualTo(1);
        double percentTotal = ((Number) JsonPath.read(after, "$.data.statusBreakdown.approved.percent")).doubleValue()
                + ((Number) JsonPath.read(after, "$.data.statusBreakdown.pending.percent")).doubleValue()
                + ((Number) JsonPath.read(after, "$.data.statusBreakdown.rejected.percent")).doubleValue();
        assertThat(percentTotal).isCloseTo(100.0, offset(1e-6));
    }

    @Test
    void articlesDefaultsToTheLastSevenDays() throws Exception {
        mockMvc.perform(get("/api/moderation/dashboard/articles").cookie(moderatorAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("LAST_7_DAYS"))
                .andExpect(jsonPath("$.data.approvedPerDay", hasSize(7)));
    }

    @Test
    void articlesRejectsAnUnknownRange() throws Exception {
        mockMvc.perform(get("/api/moderation/dashboard/articles").param("range", "LAST_WEEK").cookie(moderatorAccess))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DASHBOARD_RANGE"));
    }

    // --- Access ---

    @Test
    void dashboardRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/moderation/dashboard/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/moderation/dashboard/articles")).andExpect(status().isUnauthorized());
    }

    @Test
    void dashboardRefusesAccountsWithoutTheModeratorRole() throws Exception {
        mockMvc.perform(get("/api/moderation/dashboard/summary").cookie(authorAccess))
                .andExpect(status().isForbidden());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String expertEmail = "dash-expert-" + suffix + "@example.com";
        grantRole(registerVerified("dashexpert" + suffix, expertEmail), Role.EXPERT_REVIEWER);
        mockMvc.perform(get("/api/moderation/dashboard/articles").cookie(loginAccessCookie(expertEmail)))
                .andExpect(status().isForbidden());
    }

    // ADMIN is not granted MODERATOR; it reaches the dashboard only through the role hierarchy.
    @Test
    void adminReachesTheDashboardThroughTheRoleHierarchy() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = "dash-admin-" + suffix + "@example.com";
        grantRole(registerVerified("dashadmin" + suffix, adminEmail), Role.ADMIN);
        Cookie adminAccess = loginAccessCookie(adminEmail);

        mockMvc.perform(get("/api/moderation/dashboard/summary").cookie(adminAccess)).andExpect(status().isOk());
        mockMvc.perform(get("/api/moderation/dashboard/articles").cookie(adminAccess)).andExpect(status().isOk());
    }

    // --- Regression: the queue under the same /api/moderation prefix is unaffected ---

    @Test
    void moderationQueueStillServesModerators() throws Exception {
        mockMvc.perform(get("/api/moderation/tasks").param("state", "open").cookie(moderatorAccess))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/moderation/tasks").param("state", "nonsense").cookie(moderatorAccess))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MODERATION_TASK_STATE"));
    }
}
