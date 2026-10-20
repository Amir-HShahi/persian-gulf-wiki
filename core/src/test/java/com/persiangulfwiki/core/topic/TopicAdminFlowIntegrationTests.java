package com.persiangulfwiki.core.topic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.dto.UpdateRevisionRequest;
import com.persiangulfwiki.core.audit.entity.AuditLog;
import com.persiangulfwiki.core.audit.repository.AuditLogRepository;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Admin topic management end to end: create, edit and archive through /api/admin/topics, rename
// through the labels endpoint, and the effect on the public list and on tagging articles. The
// admin also writes the articles, as any verified account may.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TopicAdminFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    private static final String ADMIN_TOPICS = "/api/admin/topics";

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
    private AuditLogRepository auditLogRepository;

    private User admin;
    private Cookie adminCookie;

    @BeforeEach
    void signIn() throws Exception {
        String suffix = suffix();
        admin = verifiedAccount("topicadmin" + suffix, Role.ADMIN);
        adminCookie = login(admin.getEmail());
    }

    // --- smoke ---

    @Test
    void createdTopicIsListedPubliclyWithIconColorAndName() throws Exception {
        String slug = uniqueSlug();
        String created = adminWrite(post(ADMIN_TOPICS), """
                {"slug": "%s", "icon": "map-pin", "color": "#1288b6", "sortOrder": 7,
                 "names": {"fa": "جزایر آزمایشی", "en": "Test islands"}}""".formatted(slug))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value(slug))
                .andExpect(jsonPath("$.data.icon").value("map-pin"))
                .andExpect(jsonPath("$.data.color").value("#1288B6"))
                .andExpect(jsonPath("$.data.sortOrder").value(7))
                .andExpect(jsonPath("$.data.isArchived").value(false))
                .andExpect(jsonPath("$.data.names.fa").value("جزایر آزمایشی"))
                .andExpect(jsonPath("$.data.names.en").value("Test islands"))
                .andExpect(jsonPath("$.data.names.ar").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID topicId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        assertThat(topicRepository.findById(topicId).orElseThrow().getColor()).isEqualTo("#1288B6");

        mockMvc.perform(get("/api/topics").param("language", "fa").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(publicTopic(topicId, "icon")).value(contains("map-pin")))
                .andExpect(jsonPath(publicTopic(topicId, "color")).value(contains("#1288B6")))
                .andExpect(jsonPath(publicTopic(topicId, "name.value")).value(contains("Test islands")))
                .andExpect(jsonPath(publicTopic(topicId, "articleCount")).value(contains(0)));
        mockMvc.perform(get(ADMIN_TOPICS).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem(topicId.toString())));

        assertThat(auditActions()).contains("TOPIC_CREATE");
    }

    @Test
    void omittedSortOrderPlacesTheTopicLast() throws Exception {
        int first = JsonPath.read(createTopic(uniqueSlug(), null), "$.data.sortOrder");
        int second = JsonPath.read(createTopic(uniqueSlug(), null), "$.data.sortOrder");
        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void archivingHidesTheTopicAndBlocksNewTagsButKeepsExistingOnes() throws Exception {
        UUID topicId = topicId(createTopic(uniqueSlug(), null));
        UUID tagged = createArticleId(Set.of(topicId));
        UUID untagged = createArticleId(null);

        adminWrite(patch(ADMIN_TOPICS + "/" + topicId), """
                {"icon": "anchor", "color": "#00aa00", "sortOrder": 3, "isArchived": true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.icon").value("anchor"))
                .andExpect(jsonPath("$.data.color").value("#00AA00"))
                .andExpect(jsonPath("$.data.sortOrder").value(3))
                .andExpect(jsonPath("$.data.isArchived").value(true));

        mockMvc.perform(get("/api/topics").param("language", "fa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", not(hasItem(topicId.toString()))));
        mockMvc.perform(get(ADMIN_TOPICS).cookie(adminCookie))
                .andExpect(jsonPath(publicTopic(topicId, "isArchived")).value(contains(true)));

        // Not on a new article, nor added to a revision that didn't have it.
        createArticle(Set.of(topicId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));
        patchTopics(untagged, Set.of(topicId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));
        // A revision that already had it keeps it.
        patchTopics(tagged, Set.of(topicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicIds", contains(topicId.toString())));
        patchTopics(tagged, Set.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicIds", empty()));
        // Once dropped, it is gone for good from that revision too.
        patchTopics(tagged, Set.of(topicId))
                .andExpect(status().isBadRequest());

        // Restoring makes it assignable again.
        adminWrite(patch(ADMIN_TOPICS + "/" + topicId), """
                {"icon": "anchor", "color": "#00AA00", "sortOrder": 3, "isArchived": false}""")
                .andExpect(status().isOk());
        patchTopics(untagged, Set.of(topicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topicIds", contains(topicId.toString())));

        assertThat(auditActions()).contains("TOPIC_UPDATE");
    }

    @Test
    void labelsEndpointRenamesATopic() throws Exception {
        UUID topicId = topicId(createTopic(uniqueSlug(), null));

        adminWrite(put("/api/admin/labels/TOPIC/" + topicId + "/en"), """
                {"value": "Renamed"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referentKind").value("TOPIC"))
                .andExpect(jsonPath("$.data.referentId").value(topicId.toString()));
        mockMvc.perform(get("/api/topics").param("language", "fa").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath(publicTopic(topicId, "name.value")).value(contains("Renamed")))
                .andExpect(jsonPath(publicTopic(topicId, "name.isFallback")).value(contains(false)));

        adminWrite(put("/api/admin/labels/TOPIC/" + UUID.randomUUID() + "/en"), """
                {"value": "x"}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOPIC_NOT_FOUND"));
        assertThat(auditActions()).contains("LABEL_UPSERT");
    }

    // --- validation and errors ---

    @Test
    void malformedFieldsAreReportedAgainstTheField() throws Exception {
        adminWrite(post(ADMIN_TOPICS), """
                {"slug": "Not A Slug", "icon": "map-pin", "color": "#1288B6", "names": {"fa": "x"}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("slug")));
        adminWrite(post(ADMIN_TOPICS), """
                {"slug": "%s", "icon": "MapPin", "color": "#1288B6", "names": {"fa": "x"}}""".formatted(uniqueSlug()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("icon")));
        adminWrite(post(ADMIN_TOPICS), """
                {"slug": "%s", "icon": "map-pin", "color": "1288B6", "names": {"fa": "x"}}""".formatted(uniqueSlug()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("color")));
        adminWrite(post(ADMIN_TOPICS), """
                {"slug": "%s", "icon": "map-pin", "color": "#1288B6", "names": {}}""".formatted(uniqueSlug()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("names.fa")));

        UUID topicId = topicId(createTopic(uniqueSlug(), null));
        adminWrite(patch(ADMIN_TOPICS + "/" + topicId), """
                {"icon": "map-pin", "color": "#1288B6", "sortOrder": 1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("isArchived")));
        adminWrite(patch(ADMIN_TOPICS + "/" + UUID.randomUUID()), """
                {"icon": "map-pin", "color": "#1288B6", "sortOrder": 1, "isArchived": false}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOPIC_NOT_FOUND"));
    }

    @Test
    void duplicateSlugIsAConflict() throws Exception {
        String slug = uniqueSlug();
        createTopic(slug, null);
        adminWrite(post(ADMIN_TOPICS), """
                {"slug": "%s", "icon": "fish", "color": "#000000", "names": {"fa": "x"}}""".formatted(slug))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TOPIC_SLUG"));
    }

    @Test
    void onlyAdminsWithACsrfTokenMayManageTopics() throws Exception {
        String body = """
                {"slug": "%s", "icon": "fish", "color": "#000000", "names": {"fa": "x"}}""".formatted(uniqueSlug());

        mockMvc.perform(get(ADMIN_TOPICS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(ADMIN_TOPICS).with(xsrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        User moderator = verifiedAccount("topicmod" + suffix(), Role.MODERATOR);
        Cookie moderatorCookie = login(moderator.getEmail());
        mockMvc.perform(get(ADMIN_TOPICS).cookie(moderatorCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ADMIN_TOPICS).cookie(moderatorCookie).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post(ADMIN_TOPICS).cookie(adminCookie).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(topicRepository.findAll()).extracting(Topic::getSlug)
                .doesNotContain(JsonPath.<String>read(body, "$.slug"));
    }

    // --- regression ---

    // Opening TOPIC must not open SUBJECT: subject names still change only through moderation.
    @Test
    void labelsEndpointStillRefusesSubjects() throws Exception {
        adminWrite(put("/api/admin/labels/SUBJECT/" + UUID.randomUUID() + "/en"), """
                {"value": "x"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LABEL_REFERENT"));
    }

    // --- helpers ---

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

    private ResultActions adminWrite(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mockMvc.perform(request.cookie(adminCookie).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String createTopic(String slug, @Nullable Integer sortOrder) throws Exception {
        String sort = sortOrder == null ? "" : ", \"sortOrder\": " + sortOrder;
        return adminWrite(post(ADMIN_TOPICS), """
                {"slug": "%s", "icon": "fish", "color": "#1288B6", "names": {"fa": "دسته"}%s}"""
                .formatted(slug, sort))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static UUID topicId(String createdBody) {
        return UUID.fromString(JsonPath.read(createdBody, "$.data.id"));
    }

    private JsonNode articleBody() {
        return objectMapper.readTree("{\"text\":\"body\"}");
    }

    private ResultActions createArticle(@Nullable Set<UUID> topicIds) throws Exception {
        return adminWrite(post("/api/articles"), objectMapper.writeValueAsString(new CreateArticleRequest(null, "fa",
                "admin-topic-" + UUID.randomUUID(), "Title", articleBody(), null, topicIds)));
    }

    private UUID createArticleId(@Nullable Set<UUID> topicIds) throws Exception {
        String created = createArticle(topicIds)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.data.id"));
    }

    private ResultActions patchTopics(UUID articleId, Set<UUID> topicIds) throws Exception {
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/fa/revisions")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String revisionId = JsonPath.read(revisions, "$.data[0].id");
        return adminWrite(patch("/api/articles/" + articleId + "/translations/fa/revisions/" + revisionId),
                objectMapper.writeValueAsString(new UpdateRevisionRequest("Edited", articleBody(), null, topicIds)));
    }

    private List<String> auditActions() {
        return auditLogRepository.findAll().stream()
                .filter(entry -> admin.getId().equals(entry.getActorUserId()))
                .map(AuditLog::getAction)
                .toList();
    }

    private static String publicTopic(UUID topicId, String field) {
        return "$.data[?(@.id == '" + topicId + "')]." + field;
    }

    private static String uniqueSlug() {
        return "t-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
