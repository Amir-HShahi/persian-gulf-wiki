package com.persiangulfwiki.core.article;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.entity.FeaturedArticle;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.article.repository.FeaturedArticleRepository;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The home page's featured article end to end: admins schedule windows through
// /api/featured-articles, anyone reads GET /api/featured-articles/current. Each test writes its
// articles in a language of its own, so the "newest published article" fallback only ever sees
// that test's rows. The subject main image is covered in SubjectReadSideFlowIntegrationTests,
// which already builds a subject with one.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FeaturedArticleFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final String FEATURED = "/api/featured-articles";
    private static final String CURRENT = FEATURED + "/current";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private ArticleRevisionService articleRevisionService;

    @Autowired
    private FeaturedArticleRepository featuredArticleRepository;

    @Autowired
    private ArticleTranslationRepository articleTranslationRepository;

    private User admin;
    private Cookie adminCookie;
    private String language;

    @BeforeEach
    void signIn() throws Exception {
        admin = verifiedAccount("featadmin" + suffix(), Role.ADMIN);
        adminCookie = login(admin.getEmail());
        language = "x" + suffix();
    }

    // --- smoke ---

    @Test
    void withNothingScheduledTheNewestPublishedArticleIsShownAndDraftsNeverAre() throws Exception {
        createArticle("Older", true);
        String newerSlug = "feat-" + UUID.randomUUID();
        UUID newer = createArticle(newerSlug, "Newer", "Newer summary", true);
        createArticle("Unpublished draft", false);

        mockMvc.perform(get(CURRENT).param("language", language))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articleId").value(newer.toString()))
                .andExpect(jsonPath("$.data.language").value(language))
                .andExpect(jsonPath("$.data.slug").value(newerSlug))
                .andExpect(jsonPath("$.data.title").value("Newer"))
                .andExpect(jsonPath("$.data.summary").value("Newer summary"))
                .andExpect(jsonPath("$.data.currentRevisionId").value(
                        articleTranslationRepository.findByArticleIdAndLanguage(newer, language).orElseThrow()
                                .getCurrentRevisionId().toString()))
                .andExpect(jsonPath("$.data.subjectId").doesNotExist())
                .andExpect(jsonPath("$.data.mainImage").doesNotExist())
                .andExpect(jsonPath("$.data.featuredArticleId").doesNotExist());
    }

    @Test
    void latestStartedLiveWindowWinsAndRemovingItFallsBackToTheNextOne() throws Exception {
        UUID older = createArticle("Older", true);
        UUID newer = createArticle("Newer", true);
        UUID unpublished = createArticle("Draft", false);
        Instant now = Instant.now();

        UUID olderWindow = schedule(older, null, null);
        assertThat(featuredArticleRepository.findById(olderWindow).orElseThrow())
                .extracting(FeaturedArticle::getArticleId, FeaturedArticle::getFeaturedByUserId, FeaturedArticle::getEndsAt)
                .containsExactly(older, admin.getId(), null);
        UUID newerWindow = schedule(newer, now.minus(1, ChronoUnit.HOURS), null);
        // None of these may win: one has ended, one has not started, one names an unpublished article.
        schedule(newer, now.minus(3, ChronoUnit.HOURS), now.minus(2, ChronoUnit.HOURS));
        schedule(newer, now.plus(1, ChronoUnit.HOURS), null);
        schedule(unpublished, null, null);

        mockMvc.perform(get(CURRENT).param("language", language))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articleId").value(older.toString()))
                .andExpect(jsonPath("$.data.title").value("Older"))
                .andExpect(jsonPath("$.data.featuredArticleId").value(olderWindow.toString()));
        mockMvc.perform(get(FEATURED).cookie(adminCookie).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", hasItem(olderWindow.toString())))
                .andExpect(jsonPath("$.data.items[*].id", hasItem(newerWindow.toString())));

        adminWrite(delete(FEATURED + "/" + olderWindow), null).andExpect(status().isNoContent());
        assertThat(featuredArticleRepository.findById(olderWindow)).isEmpty();
        mockMvc.perform(get(CURRENT).param("language", language))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articleId").value(newer.toString()))
                .andExpect(jsonPath("$.data.featuredArticleId").value(newerWindow.toString()));
    }

    // --- validation and errors ---

    @Test
    void nothingPublishedInTheLanguageIsANotFound() throws Exception {
        createArticle("Draft only", false);
        mockMvc.perform(get(CURRENT).param("language", language))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEATURED_ARTICLE_NOT_FOUND"));
        mockMvc.perform(get(CURRENT)).andExpect(status().isBadRequest());
        mockMvc.perform(get(CURRENT).param("language", "x".repeat(21)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void badWindowsAndUnknownIdsAreRejected() throws Exception {
        UUID article = createArticle("Title", true);
        Instant now = Instant.now();

        adminWrite(post(FEATURED), """
                {"articleId": "%s", "startsAt": "%s", "endsAt": "%s"}""".formatted(article, now, now))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("endsAt"));
        adminWrite(post(FEATURED), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("articleId"));
        adminWrite(post(FEATURED), """
                {"articleId": "%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
        adminWrite(delete(FEATURED + "/" + UUID.randomUUID()), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEATURED_ARTICLE_NOT_FOUND"));
        mockMvc.perform(get(CURRENT).param("language", language))
                .andExpect(jsonPath("$.data.featuredArticleId").doesNotExist());
    }

    @Test
    void onlyAdminsWithACsrfTokenMaySchedule() throws Exception {
        UUID article = createArticle("Title", true);
        UUID window = schedule(article, null, null);
        String body = "{\"articleId\": \"%s\"}".formatted(article);

        mockMvc.perform(get(FEATURED)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(FEATURED).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        // Moderators moderate; what the home page leads with is the admins' call.
        User moderator = verifiedAccount("featmod" + suffix(), Role.MODERATOR);
        Cookie moderatorCookie = login(moderator.getEmail());
        mockMvc.perform(get(FEATURED).cookie(moderatorCookie)).andExpect(status().isForbidden());
        mockMvc.perform(post(FEATURED).cookie(moderatorCookie).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(delete(FEATURED + "/" + window).cookie(moderatorCookie).with(xsrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(FEATURED).cookie(adminCookie).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(featuredArticleRepository.findById(window)).isPresent();
        assertThat(featuredArticleRepository.findAll()).filteredOn(row -> row.getArticleId().equals(article))
                .hasSize(1);
    }

    // --- regression ---

    // Opening GET /current must not open its siblings: the schedule stays admin-only, the article
    // list stays public, and writing an article still needs an account.
    @Test
    void publicRoutesAroundTheFeaturedCardAreUnchanged() throws Exception {
        mockMvc.perform(get(FEATURED + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/articles").param("language", language)).andExpect(status().isOk());
        mockMvc.perform(post("/api/articles").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newArticle("slug-" + UUID.randomUUID(), "T", null))))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private UUID schedule(UUID articleId, @Nullable Instant startsAt, @Nullable Instant endsAt) throws Exception {
        String starts = startsAt == null ? "" : ", \"startsAt\": \"" + startsAt + "\"";
        String ends = endsAt == null ? "" : ", \"endsAt\": \"" + endsAt + "\"";
        String created = adminWrite(post(FEATURED), "{\"articleId\": \"%s\"%s%s}".formatted(articleId, starts, ends))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.articleId").value(articleId.toString()))
                .andExpect(jsonPath("$.data.featuredByUserId").value(admin.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.data.id"));
    }

    private UUID createArticle(String title, boolean isPublished) throws Exception {
        return createArticle("feat-" + UUID.randomUUID(), title, null, isPublished);
    }

    private UUID createArticle(String slug, String title, @Nullable String summary, boolean isPublished) throws Exception {
        String created = adminWrite(post("/api/articles"), objectMapper.writeValueAsString(newArticle(slug, title, summary)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        if (isPublished) {
            String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/" + language
                            + "/revisions").cookie(adminCookie))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            articleRevisionService.applyModerationOutcome(UUID.fromString(JsonPath.read(revisions, "$.data[0].id")),
                    RevisionStatus.APPROVED);
        }
        return articleId;
    }

    private CreateArticleRequest newArticle(String slug, String title, @Nullable String summary) {
        return new CreateArticleRequest(null, language, slug, title, objectMapper.readTree("{\"text\":\"body\"}"),
                summary, null);
    }

    private ResultActions adminWrite(MockHttpServletRequestBuilder request, @Nullable String json) throws Exception {
        MockHttpServletRequestBuilder withAuth = request.cookie(adminCookie).with(xsrf());
        return mockMvc.perform(json == null ? withAuth
                : withAuth.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private User verifiedAccount(String username, Role role) throws Exception {
        String email = username + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        user = userRepository.save(user);
        userRoleRepository.save(UserRole.builder().user(user).role(role).build());
        return user;
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
