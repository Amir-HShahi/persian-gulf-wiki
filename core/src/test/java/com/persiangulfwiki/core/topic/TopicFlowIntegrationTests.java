package com.persiangulfwiki.core.topic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.dto.CreateTranslationRequest;
import com.persiangulfwiki.core.article.dto.UpdateRevisionRequest;
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

    // Through the real submit -> claim -> decide flow.
    private void publish(UUID articleId, String language) throws Exception {
        String path = revisionPath(articleId, language);
        mockMvc.perform(post(path + "/submit")
                        .cookie(author, csrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrf.getValue())))
                .andExpect(status().isOk());
        UUID taskId = moderationTaskRepository.findByRevisionId(revisionId(articleId, language)).orElseThrow().getId();
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/claim")
                        .cookie(moderator, csrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrf.getValue())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/moderation/tasks/" + taskId + "/decide")
                        .cookie(moderator, csrf)
                        .header("X-XSRF-TOKEN", maskCsrfToken(csrf.getValue()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DecideRequest(Decision.APPROVE, null))))
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
    void articlesAboutADeletedSubjectAreNotCounted() throws Exception {
        UUID topic = newTopic();
        Subject subject = subjectRepository.save(Subject.builder().kind(SubjectKind.ISLAND).build());
        UUID articleId = createArticleId(subject.getId(), Set.of(topic));
        publish(articleId, "fa");
        assertThat(articleCount(topic, "fa")).isEqualTo(1);

        subject.setDeletedAt(Instant.now());
        subjectRepository.save(subject);

        assertThat(articleCount(topic, "fa")).isZero();
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
