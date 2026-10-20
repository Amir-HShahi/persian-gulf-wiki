package com.persiangulfwiki.core.topic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.dto.CreateTranslationRequest;
import com.persiangulfwiki.core.article.dto.UpdateRevisionRequest;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.label.repository.LabelRepository;
import com.persiangulfwiki.core.moderation.dto.DecideRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.topic.entity.Topic;
import com.persiangulfwiki.core.topic.repository.TopicRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.persiangulfwiki.core.CsrfTestSupport.maskCsrfToken;
import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Topics end to end: an author tags the canonical revision, a moderator's approval makes the tags
// live, and GET /api/topics counts the article in every language it is published in. Each test
// mints its own topics, so counts in the shared database only ever reflect this test's articles.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TopicFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private ArticleTranslationRepository articleTranslationRepository;

    @Autowired
    private ArticleRevisionRepository articleRevisionRepository;

    private Cookie author;
    private Cookie moderator;
    private Cookie csrf;

    @BeforeEach
    void signIn() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        author = login(verifiedAccount("topicauthor" + suffix, false));
        moderator = login(verifiedAccount("topicmod" + suffix, true));
        csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    // --- helpers ---

    private String verifiedAccount(String username, boolean isModerator) throws Exception {
        String email = username + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);
        if (isModerator) {
            userRoleRepository.save(UserRole.builder().user(user).role(Role.MODERATOR).build());
        }
        return email;
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID newTopic() {
        return newTopic(500);
    }

    private UUID newTopic(int sortOrder) {
        return topicRepository.save(Topic.builder()
                .slug("test-" + UUID.randomUUID().toString().substring(0, 12))
                .icon("map-pin")
                .color("#1288B6")
                .sortOrder(sortOrder)
                .build()).getId();
    }

    private JsonNode body() {
        return objectMapper.readTree("{\"text\":\"body\"}");
    }

    private ResultActions write(String method, String path, Object request) throws Exception {
        return mockMvc.perform((method.equals("POST") ? post(path) : patch(path))
                .cookie(author, csrf)
                .header("X-XSRF-TOKEN", maskCsrfToken(csrf.getValue()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions createArticle(@Nullable UUID subjectId, @Nullable Set<UUID> topicIds) throws Exception {
        return write("POST", "/api/articles", new CreateArticleRequest(subjectId, "fa",
                "topic-" + UUID.randomUUID(), "Title", body(), null, topicIds));
    }

    private UUID createArticleId(@Nullable UUID subjectId, @Nullable Set<UUID> topicIds) throws Exception {
        String created = createArticle(subjectId, topicIds)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.data.id"));
    }

    private void addEnglish(UUID articleId) throws Exception {
        write("POST", "/api/articles/" + articleId + "/translations", new CreateTranslationRequest("en",
                "topic-en-" + UUID.randomUUID(), "Title", body(), null))
                .andExpect(status().isCreated());
    }

    private UUID revisionId(UUID articleId, String language) throws Exception {
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/" + language + "/revisions")
                        .cookie(author))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(revisions, "$.data[0].id"));
    }

    private String revisionPath(UUID articleId, String language) throws Exception {
        return "/api/articles/" + articleId + "/translations/" + language + "/revisions/" + revisionId(articleId, language);
    }

    private ResultActions patchTopics(UUID articleId, String language, @Nullable Set<UUID> topicIds) throws Exception {
        return write("PATCH", revisionPath(articleId, language),
                new UpdateRevisionRequest("Edited", body(), null, topicIds));
    }

    private void publish(UUID articleId, String language) throws Exception {
        review(articleId, language, Decision.APPROVE);
    }

    private void submit(UUID articleId, String language) throws Exception {
        mockMvc.perform(post(revisionPath(articleId, language) + "/submit")
                        .cookie(author, csrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrf.getValue())))
                .andExpect(status().isOk());
    }

    // Through the real submit -> claim -> decide flow.
    private void review(UUID articleId, String language, Decision decision) throws Exception {
        submit(articleId, language);
        UUID taskId = moderationTaskRepository.findByRevisionId(revisionId(articleId, language)).orElseThrow().getId();
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/claim")
                        .cookie(moderator, csrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrf.getValue())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/decide")
                        .cookie(moderator, csrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrf.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecideRequest(decision, decision == Decision.APPROVE ? null : "Not suitable"))))
                .andExpect(status().isOk());
    }

    // Anonymous, like the home page.
    private long articleCount(UUID topicId, String language) throws Exception {
        String topics = mockMvc.perform(get("/api/topics").param("language", language))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> counts = JsonPath.read(topics, "$.data[?(@.id == '" + topicId + "')].articleCount");
        assertThat(counts).hasSize(1);
        return counts.getFirst();
    }

    private String slugOf(UUID topicId) {
        return topicRepository.findById(topicId).orElseThrow().getSlug();
    }

    // A second revision of the canonical translation, after the approved one. No endpoint creates
    // one yet, so it is inserted directly, as a later edit would be.
    private void addLaterCanonicalRevision(UUID articleId, RevisionStatus status, UUID topicId) throws Exception {
        UUID translationId = articleTranslationRepository.findByArticleIdAndLanguage(articleId, "fa").orElseThrow().getId();
        ArticleRevision approved = articleRevisionRepository.findById(revisionId(articleId, "fa")).orElseThrow();
        ArticleRevision later = ArticleRevision.builder()
                .translationId(translationId)
                .revisionNumber(approved.getRevisionNumber() + 1)
                .parentRevisionId(approved.getId())
                .title("Later")
                .body("{\"text\":\"later\"}")
                .status(status)
                .authorId(approved.getAuthorId())
                .build();
        later.setTopicIds(Set.of(topicId));
        articleRevisionRepository.save(later);
    }

    // Anonymous, like the home page's topic cards.
    private ResultActions listByTopic(String topicSlug, String language) throws Exception {
        return mockMvc.perform(get("/api/articles").param("language", language).param("topic", topicSlug)
                        .param("size", "100"))
                .andExpect(status().isOk());
    }

    // --- smoke ---

    @Test
    void approvedCanonicalTopicsCountInEveryPublishedLanguage() throws Exception {
        UUID topic = newTopic();
        UUID untagged = createArticleId(null, null);
        UUID articleId = createArticleId(null, Set.of(topic));
        mockMvc.perform(get(revisionPath(articleId, "fa")).cookie(author))
                .andExpect(jsonPath("$.data.topicIds", contains(topic.toString())));

        assertThat(articleCount(topic, "fa")).isZero();

        publish(articleId, "fa");
        publish(untagged, "fa");
        assertThat(articleCount(topic, "fa")).isEqualTo(1);
        assertThat(articleCount(topic, "en")).isZero();

        addEnglish(articleId);
        mockMvc.perform(get("/api/articles/" + articleId + "/translations/en").cookie(author))
                .andExpect(jsonPath("$.data.topicIds", contains(topic.toString())));
        assertThat(articleCount(topic, "en")).isZero();

        publish(articleId, "en");
        assertThat(articleCount(topic, "en")).isEqualTo(1);
        mockMvc.perform(get("/api/articles/" + articleId + "/translations/en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicIds", contains(topic.toString())));
        mockMvc.perform(get(revisionPath(articleId, "en")))
                .andExpect(jsonPath("$.data.topicIds", empty()));
    }

    @Test
    void articlesAboutADeletedSubjectAreNeitherCountedNorListed() throws Exception {
        UUID topic = newTopic();
        Subject subject = subjectRepository.save(Subject.builder().kind(SubjectKind.ISLAND).build());
        UUID articleId = createArticleId(subject.getId(), Set.of(topic));
        publish(articleId, "fa");
        assertThat(articleCount(topic, "fa")).isEqualTo(1);
        listByTopic(slugOf(topic), "fa").andExpect(jsonPath("$.data.total").value(1));

        subject.setDeletedAt(Instant.now());
        subjectRepository.save(subject);

        assertThat(articleCount(topic, "fa")).isZero();
        listByTopic(slugOf(topic), "fa")
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items", empty()));
        mockMvc.perform(get("/api/articles").param("language", "fa").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + articleId + "')]", empty()));
    }

    // Only the canonical translation's approved revision counts: a topic on a rejected revision, on
    // one still pending, or on a pending/rejected revision after an untagged approved one doesn't
    // put the article under the topic. The list's total is the topic's articleCount in each language.
    @Test
    void topicFilterMatchesOnlyTheApprovedCanonicalRevisionAndTotalsItsCount() throws Exception {
        UUID topic = newTopic();
        String slug = slugOf(topic);
        UUID live = createArticleId(null, Set.of(topic));
        publish(live, "fa");
        addEnglish(live);
        publish(live, "en");
        UUID untagged = createArticleId(null, null);
        publish(untagged, "fa");
        UUID rejected = createArticleId(null, Set.of(topic));
        review(rejected, "fa", Decision.REJECT);
        UUID pending = createArticleId(null, Set.of(topic));
        submit(pending, "fa");
        UUID laterPending = createArticleId(null, null);
        publish(laterPending, "fa");
        addLaterCanonicalRevision(laterPending, RevisionStatus.PENDING, topic);
        UUID laterRejected = createArticleId(null, null);
        publish(laterRejected, "fa");
        addLaterCanonicalRevision(laterRejected, RevisionStatus.REJECTED, topic);

        for (String language : List.of("fa", "en")) {
            listByTopic(slug, language)
                    .andExpect(jsonPath("$.data.items[*].id", contains(live.toString())))
                    .andExpect(jsonPath("$.data.items[0].language").value(language))
                    .andExpect(jsonPath("$.data.total").value(articleCount(topic, language)))
                    .andExpect(jsonPath("$.data.total").value(1));
        }
    }

    @Test
    void topicFilterCombinesWithSubjectKind() throws Exception {
        UUID topic = newTopic();
        String slug = slugOf(topic);
        UUID island = subjectRepository.save(Subject.builder().kind(SubjectKind.ISLAND).build()).getId();
        UUID port = subjectRepository.save(Subject.builder().kind(SubjectKind.PORT).build()).getId();
        UUID taggedIsland = createArticleId(island, Set.of(topic));
        UUID taggedPort = createArticleId(port, Set.of(topic));
        UUID untaggedIsland = createArticleId(island, null);
        for (UUID articleId : List.of(taggedIsland, taggedPort, untaggedIsland)) {
            publish(articleId, "fa");
        }

        listByTopic(slug, "fa")
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(taggedIsland.toString(), taggedPort.toString())))
                .andExpect(jsonPath("$.data.total").value(articleCount(topic, "fa")));
        mockMvc.perform(get("/api/articles").param("language", "fa").param("topic", slug).param("subjectKind", "island"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", contains(taggedIsland.toString())))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/articles").param("language", "fa").param("topic", slug).param("subjectId", port.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", contains(taggedPort.toString())))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    // Both match nothing rather than failing: an archived topic is gone from the home page, and the
    // list treats it like one that never existed.
    @Test
    void unknownOrArchivedTopicGivesAnEmptyPage() throws Exception {
        listByTopic("no-such-topic", "fa")
                .andExpect(jsonPath("$.data.items", empty()))
                .andExpect(jsonPath("$.data.total").value(0));

        UUID topicId = newTopic();
        UUID articleId = createArticleId(null, Set.of(topicId));
        publish(articleId, "fa");
        listByTopic(slugOf(topicId), "fa").andExpect(jsonPath("$.data.total").value(1));

        Topic topic = topicRepository.findById(topicId).orElseThrow();
        topic.setArchived(true);
        topicRepository.save(topic);

        listByTopic(slugOf(topicId), "fa")
                .andExpect(jsonPath("$.data.items", empty()))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // Sort orders below anything other tests create, so these two head the shared list.
    @Test
    void topicsListAnonymouslyInDisplayOrderWithLocalizedNames() throws Exception {
        UUID second = newTopic(Integer.MIN_VALUE + 1);
        UUID first = newTopic(Integer.MIN_VALUE);
        labelRepository.save(Label.builder().topicId(first).language("fa").value("زیست‌بوم").build());
        labelRepository.save(Label.builder().topicId(first).language("en").value("Marine life").build());
        labelRepository.save(Label.builder().topicId(second).language("fa").value("جزایر").build());

        mockMvc.perform(get("/api/topics").param("language", "fa").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(first.toString()))
                .andExpect(jsonPath("$.data[0].icon").value("map-pin"))
                .andExpect(jsonPath("$.data[0].color").value("#1288B6"))
                .andExpect(jsonPath("$.data[0].name.value").value("Marine life"))
                .andExpect(jsonPath("$.data[0].name.isFallback").value(false))
                .andExpect(jsonPath("$.data[1].id").value(second.toString()))
                .andExpect(jsonPath("$.data[1].name.value").value("جزایر"))
                .andExpect(jsonPath("$.data[1].name.isFallback").value(true));
    }

    // V31's seed: other tests' topics interleave in the shared list, so only the seeded slugs are compared.
    @Test
    void seededTopicsListInOrderWithANameInEveryLanguage() throws Exception {
        List<String> seeded = List.of("marine-life", "plants-and-algae", "islands", "ports-and-oil-terminals",
                "shipping-and-transit", "pollution-and-environment", "history-and-archaeology", "economy-and-trade",
                "geography-and-climate", "culture-and-ethnography");
        for (String language : List.of("fa", "en", "ar")) {
            String topics = mockMvc.perform(get("/api/topics").param("language", language)
                            .header(HttpHeaders.ACCEPT_LANGUAGE, language))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<String> slugs = JsonPath.read(topics, "$.data[*].slug");
            assertThat(slugs.stream().filter(seeded::contains).toList()).isEqualTo(seeded);
            List<Boolean> fallbacks = JsonPath.read(topics, "$.data[?(@.slug == 'islands')].name.isFallback");
            assertThat(fallbacks).containsExactly(false);
        }
        mockMvc.perform(get("/api/topics").param("language", "ar").header(HttpHeaders.ACCEPT_LANGUAGE, "ar"))
                .andExpect(jsonPath("$.data[?(@.slug == 'islands')].name.value").value(contains("الجزر")))
                .andExpect(jsonPath("$.data[?(@.slug == 'islands')].icon").value(contains("map-pin")));
    }

    @Test
    void topicsWithoutLanguageAreRejected() throws Exception {
        mockMvc.perform(get("/api/topics"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/topics").param("language", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // --- revision rules ---

    @Test
    void patchWithoutTopicIdsKeepsThemAndAListReplacesThem() throws Exception {
        UUID first = newTopic();
        UUID second = newTopic();
        UUID articleId = createArticleId(null, Set.of(first));

        patchTopics(articleId, "fa", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Edited"))
                .andExpect(jsonPath("$.data.topicIds", contains(first.toString())));
        patchTopics(articleId, "fa", Set.of(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicIds", contains(second.toString())));
        patchTopics(articleId, "fa", Set.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicIds", empty()));
    }

    @Test
    void onlyTheCanonicalRevisionMayCarryTopics() throws Exception {
        UUID topic = newTopic();
        UUID articleId = createArticleId(null, Set.of(topic));
        addEnglish(articleId);

        patchTopics(articleId, "en", Set.of(topic))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));
        patchTopics(articleId, "en", Set.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicIds", empty()));
    }

    @Test
    void unknownOrTooManyTopicIdsAreRejected() throws Exception {
        createArticle(null, Set.of(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));
        createArticle(null, Set.of(newTopic(), newTopic(), newTopic(), newTopic(), newTopic(), newTopic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));

        UUID articleId = createArticleId(null, null);
        patchTopics(articleId, "fa", Set.of(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));
    }

    // --- regression ---

    // Without `topic`, tagged and untagged articles are listed alike, as before the filter existed.
    @Test
    void listWithoutTopicStillIncludesTaggedAndUntaggedArticles() throws Exception {
        UUID tagged = createArticleId(null, Set.of(newTopic()));
        UUID untagged = createArticleId(null, null);
        publish(tagged, "fa");
        publish(untagged, "fa");

        mockMvc.perform(get("/api/articles").param("language", "fa").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + tagged + "')]").isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + untagged + "')]").isNotEmpty());
    }

    // An article created and edited without ever mentioning topics behaves as before: the PATCH
    // still replaces the text, and the revision simply has no topics.
    @Test
    void articlesWithoutTopicsStillCreateEditAndPublish() throws Exception {
        UUID articleId = createArticleId(null, null);
        patchTopics(articleId, "fa", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Edited"))
                .andExpect(jsonPath("$.data.topicIds", empty()));
        publish(articleId, "fa");
        mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicIds", empty()));
    }
}
