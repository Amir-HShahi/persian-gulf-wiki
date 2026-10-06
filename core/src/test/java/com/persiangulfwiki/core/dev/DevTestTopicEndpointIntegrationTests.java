package com.persiangulfwiki.core.dev;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.topic.entity.Topic;
import com.persiangulfwiki.core.topic.repository.TopicRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Dev profile only, like DevTestAttributeEndpointIntegrationTests: POST/DELETE /api/dev/test-topics,
// topicIds on a minted article, and the topic sweeper.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevTestTopicEndpointIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private DevTestTopicSweeper topicSweeper;

    private ResultActions mintTopic(String json) throws Exception {
        return mockMvc.perform(post("/api/dev/test-topics").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID mintTopicId(String json) throws Exception {
        String body = mintTopic(json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.topicId"));
    }

    private UUID mintPublishedArticle(UUID topicId) throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revisionStatus\": \"APPROVED\", \"isMain\": false, \"topicIds\": [\"%s\"]}"
                                .formatted(topicId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.articleId"));
    }

    private String topicJsonPath(UUID topicId, String field) {
        return "$.data[?(@.id == '" + topicId + "')]." + field;
    }

    @Test
    void mintedTopicIsListedAndCountsMintedArticles() throws Exception {
        UUID topicId = mintTopicId("{\"faLabel\": \"دستهٔ آزمایشی\"}");
        Topic topic = topicRepository.findById(topicId).orElseThrow();
        assertThat(topic.getDevMarker()).isEqualTo(DevTestFixtures.MARKER);
        assertThat(topic.getSlug()).startsWith("e2e-");

        mintPublishedArticle(topicId);

        // No English name: the Farsi one is served as a fallback.
        mockMvc.perform(get("/api/topics").param("language", "fa").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(topicJsonPath(topicId, "articleCount")).value(1))
                .andExpect(jsonPath(topicJsonPath(topicId, "name.value")).value("دستهٔ آزمایشی"))
                .andExpect(jsonPath(topicJsonPath(topicId, "name.isFallback")).value(true));
    }

    @Test
    void mintedTopicWithoutLabelsHasNoName() throws Exception {
        UUID topicId = mintTopicId("{}");
        String body = mockMvc.perform(get("/api/topics").param("language", "fa"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Object> names = JsonPath.read(body, topicJsonPath(topicId, "name"));
        assertThat(names).containsExactly((Object) null);
    }

    @Test
    void mintRejectsAMalformedSlug() throws Exception {
        mintTopic("{\"slug\": \"Not A Slug\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("slug"));
    }

    @Test
    void mintedArticleRejectsAnUnknownTopic() throws Exception {
        mockMvc.perform(post("/api/dev/test-articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicIds\": [\"%s\"]}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topicIds"));
    }

    @Test
    void deleteRefusesATopicInUseUntilItsArticleIsGone() throws Exception {
        UUID topicId = mintTopicId("{}");
        UUID articleId = mintPublishedArticle(topicId);

        mockMvc.perform(delete("/api/dev/test-topics/" + topicId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEV_TEST_TOPIC_IN_USE"));

        mockMvc.perform(delete("/api/dev/test-articles/" + articleId)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/dev/test-topics/" + topicId)).andExpect(status().isNoContent());
        assertThat(topicRepository.findById(topicId)).isEmpty();
    }

    @Test
    void deleteRefusesASeededTopic() throws Exception {
        Topic seeded = topicRepository.findAllByOrderBySortOrderAscSlugAsc().getFirst();
        assertThat(seeded.getDevMarker()).isNull();

        mockMvc.perform(delete("/api/dev/test-topics/" + seeded.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOPIC_NOT_FOUND"));
        assertThat(topicRepository.findById(seeded.getId())).isPresent();
    }

    @Test
    void devLabelsCanNameAMintedTopic() throws Exception {
        UUID topicId = mintTopicId("{}");
        mockMvc.perform(post("/api/dev/test-labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"referentKind\": \"TOPIC\", \"referentId\": \"%s\", \"language\": \"en\", \"value\": \"Test\"}"
                                .formatted(topicId)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/topics").param("language", "fa").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath(topicJsonPath(topicId, "name.value")).value("Test"));
    }

    @Test
    void sweeperRemovesUnusedMintedTopicsAndKeepsUsedAndSeededOnes() throws Exception {
        UUID unused = mintTopicId("{}");
        UUID used = mintTopicId("{}");
        mintPublishedArticle(used);
        long seededBefore = topicRepository.findAll().stream().filter(topic -> topic.getDevMarker() == null).count();

        topicSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(topicRepository.findById(unused)).isEmpty();
        assertThat(topicRepository.findById(used)).isPresent();
        assertThat(topicRepository.findAll().stream().filter(topic -> topic.getDevMarker() == null).count())
                .isEqualTo(seededBefore);
        mockMvc.perform(get("/api/topics").param("language", "fa"))
                .andExpect(jsonPath("$.data[0].slug", startsWith("marine-life")));
    }
}
