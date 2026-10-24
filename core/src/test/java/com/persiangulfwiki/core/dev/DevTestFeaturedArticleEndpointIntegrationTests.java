package com.persiangulfwiki.core.dev;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.entity.FeaturedArticle;
import com.persiangulfwiki.core.article.repository.FeaturedArticleRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Dev profile only, like DevTestTopicEndpointIntegrationTests: POST/DELETE
// /api/dev/test-featured-articles, the marker check on delete, and the sweeper.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevTestFeaturedArticleEndpointIntegrationTests {

    private static final String ENDPOINT = "/api/dev/test-featured-articles";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeaturedArticleRepository featuredArticleRepository;

    @Autowired
    private DevTestFeaturedArticleSweeper sweeper;

    @Test
    void mintedWindowFeaturesTheArticleAndDeleteRemovesIt() throws Exception {
        String language = "x" + UUID.randomUUID().toString().substring(0, 8);
        UUID articleId = mintPublishedArticle(language);
        UUID windowId = mintWindowId("{\"articleId\": \"%s\"}".formatted(articleId));
        assertThat(featuredArticleRepository.findById(windowId).orElseThrow().getDevMarker())
                .isEqualTo(DevTestFixtures.MARKER);

        mockMvc.perform(get("/api/featured-articles/current").param("language", language))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.articleId").value(articleId.toString()))
                .andExpect(jsonPath("$.data.featuredArticleId").value(windowId.toString()));

        mockMvc.perform(delete(ENDPOINT + "/" + windowId)).andExpect(status().isNoContent());
        assertThat(featuredArticleRepository.findById(windowId)).isEmpty();
        mockMvc.perform(delete(ENDPOINT + "/" + windowId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEATURED_ARTICLE_NOT_FOUND"));
    }

    @Test
    void expiredWindowIsMintableButBadOnesAreRefused() throws Exception {
        UUID articleId = mintPublishedArticle("fa");
        Instant now = Instant.now();
        mintWindow("{\"articleId\": \"%s\", \"startsAt\": \"%s\", \"endsAt\": \"%s\"}"
                .formatted(articleId, now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS)))
                .andExpect(status().isCreated());
        mintWindow("{\"articleId\": \"%s\", \"startsAt\": \"%s\", \"endsAt\": \"%s\"}"
                .formatted(articleId, now, now.minus(1, ChronoUnit.HOURS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endsAt"));
        mintWindow("{\"articleId\": \"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
    }

    // A window an admin scheduled is not this endpoint's to delete, nor the sweeper's.
    @Test
    void handMadeWindowsAreNeverDeleted() throws Exception {
        UUID articleId = mintPublishedArticle("fa");
        FeaturedArticle handMade = featuredArticleRepository.save(FeaturedArticle.builder()
                .articleId(articleId)
                .startsAt(Instant.now().plus(365, ChronoUnit.DAYS))
                .build());
        UUID minted = mintWindowId("{\"articleId\": \"%s\", \"startsAt\": \"%s\"}"
                .formatted(articleId, Instant.now().plus(365, ChronoUnit.DAYS)));

        mockMvc.perform(delete(ENDPOINT + "/" + handMade.getId()))
                .andExpect(status().isNotFound());
        sweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.MINUTES));

        assertThat(featuredArticleRepository.findById(handMade.getId())).isPresent();
        assertThat(featuredArticleRepository.findById(minted)).isEmpty();
        featuredArticleRepository.delete(handMade);
    }

    private UUID mintPublishedArticle(String language) throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revisionStatus\": \"APPROVED\", \"isMain\": false, \"canonicalLanguage\": \"%s\"}"
                                .formatted(language)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.articleId"));
    }

    private ResultActions mintWindow(String json) throws Exception {
        return mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID mintWindowId(String json) throws Exception {
        String body = mintWindow(json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.featuredArticleId"));
    }
}
