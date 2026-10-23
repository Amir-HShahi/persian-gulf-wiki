package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.FeaturedArticle;
import com.persiangulfwiki.core.article.entity.TranslationState;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.article.repository.FeaturedArticleRepository;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoArticle;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoAsset;
import com.persiangulfwiki.core.media.storage.MediaStorage;
import com.persiangulfwiki.core.media.storage.StorageBucket;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.topic.repository.TopicRepository;
import com.persiangulfwiki.core.user.repository.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The demo dataset end to end: seeded through the real approval paths, read back through the public
// routes the frontend uses, and wiped. Expected numbers come from the shipped dataset itself, not
// from literals, so adding an article never breaks this test.
//
// The buckets are pointed at the ones TestcontainersConfiguration creates: application-dev.yaml
// would otherwise name the local compose stack's buckets, which do not exist in the test MinIO.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.storage.staging-bucket=test-staging",
        "app.storage.media-bucket=test-media",
        "app.storage.public-base-url=http://localhost/test-media/public"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevDemoContentEndpointIntegrationTests {

    private static final String QESHM_FA_SLUG = "جزیره-قشم";

    private static DevDemoDataset dataset;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ArticleTranslationRepository articleTranslationRepository;

    @Autowired
    private FeaturedArticleRepository featuredArticleRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private MediaStorage mediaStorage;

    @MockitoSpyBean
    private DevDemoContentWiper wiper;

    @BeforeAll
    static void loadDataset() {
        dataset = DevDemoDataset.load(JsonMapper.builder().build());
    }

    // Leaves the shared database as it found it, whatever a test did.
    @AfterEach
    void wipe() throws Exception {
        reset(wiper);
        awaitFinished();
        mockMvc.perform(delete("/api/dev/demo-content")).andExpect(status().isNoContent());
    }

    @Test
    void seedingCreatesTheWholeDatasetThroughThePublicRoutes() throws Exception {
        Map<SubjectKind, Long> subjectsByKind = dataset.articles().stream()
                .filter(DemoArticle::hasSubject)
                .collect(Collectors.groupingBy(article -> article.subject().kind(), Collectors.counting()));
        int images = dataset.articles().stream().mapToInt(article -> article.gallery() == null ? 0 : article.gallery().size()).sum();
        long panoramas = dataset.articles().stream().filter(article -> article.panorama() != null).count();

        JsonNode response = seed();

        assertThat(response.get("articles").asInt()).isEqualTo(dataset.articles().size());
        assertThat(response.get("translations").asInt()).isEqualTo(dataset.articles().size() * 3);
        assertThat(response.get("users").asInt()).isEqualTo(dataset.users().size());
        assertThat(response.get("sources").asInt()).isEqualTo(dataset.sources().size());
        assertThat(response.get("images").asInt()).isEqualTo(images);
        assertThat(response.get("panoramas").asInt()).isEqualTo((int) panoramas);
        assertThat(response.get("featuredWindows").asInt()).isEqualTo(2);
        subjectsByKind.forEach((kind, count) ->
                assertThat(response.get("subjectsByKind").get(kind.name()).asLong()).as(kind.name()).isEqualTo(count));

        // The species bank: every species, and a non-empty count under every IUCN status.
        JsonNode species = getData("/api/subjects?kind=SPECIES&size=1", "en");
        assertThat(species.get("total").asLong()).isGreaterThanOrEqualTo(subjectsByKind.get(SubjectKind.SPECIES));
        JsonNode iucn = facet(species, "iucn_status");
        for (String status : List.of("CR", "EN", "VU", "NT", "LC", "DD")) {
            assertThat(option(iucn, status).get("count").asLong()).as(status).isPositive();
        }

        // The home page's topic tiles: at least as many articles as the dataset puts under each.
        Map<String, Long> perTopic = new HashMap<>();
        dataset.articles().forEach(article -> article.topics().forEach(topic -> perTopic.merge(topic, 1L, Long::sum)));
        for (JsonNode topic : getData("/api/topics?language=fa", "fa")) {
            assertThat(topic.get("articleCount").asLong()).as(topic.get("slug").asString())
                    .isGreaterThanOrEqualTo(perTopic.getOrDefault(topic.get("slug").asString(), 0L));
        }

        // The reader page: a TipTap body whose subject links resolve, in all three languages.
        JsonNode qeshm = getData("/api/articles/by-slug/" + QESHM_FA_SLUG, "fa");
        assertThat(qeshm.get("revision").get("body").get("type").asString()).isEqualTo("doc");
        assertThat(qeshm.get("subjectLinks").size()).isGreaterThanOrEqualTo(4);
        assertThat(qeshm.get("otherTranslations").size()).isEqualTo(2);
        assertThat(qeshm.get("isMain").asBoolean()).isTrue();

        // The infobox: every field the dataset gives, the derived ones, and a main image whose
        // variants are really in storage.
        UUID subjectId = UUID.fromString(qeshm.get("subjectId").asString());
        JsonNode infobox = getData("/api/subjects/" + subjectId + "/infobox", "fa");
        Set<String> rows = new HashSet<>();
        infobox.get("groups").forEach(group -> group.get("rows").forEach(row -> rows.add(row.get("attribute").asString())));
        dataset.articlesByKey().get("qeshm-island").subject().facts().forEach(fact -> assertThat(rows).contains(fact.attribute()));
        assertThat(rows).contains("population_density");
        // has_360_tour is a filter, not an infobox row (V27); Qeshm's gallery has a published panorama.
        assertThat(subjectRepository.hasPublished360Tour(subjectId)).isTrue();
        JsonNode variants = infobox.get("mainImage").get("variants");
        assertThat(variants.size()).isEqualTo(4);
        UUID mainImageId = UUID.fromString(infobox.get("mainImage").get("mediaId").asString());
        assertThat(mediaStorage.head(StorageBucket.MEDIA, "public/" + mainImageId + "/w640.webp")).isPresent();
        assertThat(infobox.get("revision")).isNotNull();

        // The home page's featured block: the Strait of Hormuz now, South Pars next.
        List<FeaturedArticle> windows = featuredArticleRepository.findAll().stream()
                .filter(window -> DevDemoContent.MARKER.equals(window.getDevMarker()))
                .toList();
        assertThat(windows).hasSize(2);
        UUID hormuzId = articleTranslationRepository.findBySlug("strait-of-hormuz").orElseThrow().getArticleId();
        assertThat(windows).anySatisfy(window -> {
            assertThat(window.getArticleId()).isEqualTo(hormuzId);
            assertThat(window.getStartsAt()).isBefore(Instant.now());
        });

        // The V31 topics are only referenced, never created or changed.
        assertThat(topicRepository.findAll()).allSatisfy(topic -> assertThat(topic.getDevMarker()).isNull());
    }

    @Test
    void reseedingReplacesTheDatasetWithTheSameContent() throws Exception {
        seed();
        Set<String> firstSlugs = demoSlugs();
        long firstArticles = articleRepository.countByDevMarker(DevDemoContent.MARKER);

        seed();

        assertThat(articleRepository.countByDevMarker(DevDemoContent.MARKER)).isEqualTo(firstArticles);
        assertThat(demoSlugs()).isEqualTo(firstSlugs);
    }

    @Test
    void aSlugTakenByOtherContentGetsASuffixAndTheSeedSucceeds() throws Exception {
        Article handMade = articleRepository.save(Article.builder().canonicalLanguage("en").isMain(false).build());
        articleTranslationRepository.save(ArticleTranslation.builder()
                .articleId(handMade.getId())
                .language("en")
                .slug("qeshm-island")
                .translationState(TranslationState.UP_TO_DATE)
                .build());
        try {
            seed();

            assertThat(articleTranslationRepository.findBySlug("qeshm-island").orElseThrow().getArticleId())
                    .isEqualTo(handMade.getId());
            assertThat(articleTranslationRepository.findBySlug("qeshm-island-demo")).isPresent();
        } finally {
            wipe();
            articleRepository.deleteById(handMade.getId());
        }
    }

    @Test
    void wipingRemovesEverythingTheSeedMadeButKeepsWhatOtherContentDependsOn() throws Exception {
        seed();
        UUID khuranId = articleRepository.findById(
                articleTranslationRepository.findBySlug("khuran-strait").orElseThrow().getArticleId())
                .orElseThrow().getSubjectId();
        // An E2E fixture, minted through its own route: a different marker, so the wipe never sees it.
        MvcResult fixture = mockMvc.perform(post("/api/dev/test-subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID fixtureId = UUID.fromString(objectMapper.readTree(fixture.getResponse().getContentAsString())
                .get("subjectId").asString());
        // Content a person made about a demo subject: the wipe must not delete it, or fail on it.
        Article handMade = articleRepository.save(Article.builder()
                .subjectId(khuranId)
                .canonicalLanguage("fa")
                .isMain(false)
                .build());
        try {
            mockMvc.perform(delete("/api/dev/demo-content")).andExpect(status().isNoContent());

            assertThat(articleRepository.findById(handMade.getId())).isPresent();
            assertThat(subjectRepository.findById(fixtureId)).isPresent();
            assertThat(subjectRepository.findById(khuranId)).isPresent();
            assertThat(articleRepository.countByDevMarker(DevDemoContent.MARKER)).isZero();
            assertThat(subjectRepository.countByDevMarker(DevDemoContent.MARKER)).isEqualTo(1);
            DemoAsset asset = dataset.assets().getFirst();
            assertThat(mediaStorage.head(StorageBucket.MEDIA,
                    DevDemoContent.LIBRARY_PREFIX + asset.key() + "/" + asset.variants().getFirst().file())).isEmpty();
        } finally {
            articleRepository.deleteById(handMade.getId());
            mockMvc.perform(delete("/api/dev/test-subjects/{subjectId}", fixtureId))
                    .andExpect(status().isNoContent());
        }

        // With nothing depending on it any more, the next wipe takes the rest.
        mockMvc.perform(delete("/api/dev/demo-content")).andExpect(status().isNoContent());
        assertThat(subjectRepository.countByDevMarker(DevDemoContent.MARKER)).isZero();
        assertThat(userRepository.countByEmailStartingWith(DevDemoContent.EMAIL_PREFIX)).isZero();
    }

    @Test
    void wipingWhenThereIsNothingToWipeSucceeds() throws Exception {
        mockMvc.perform(delete("/api/dev/demo-content")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/dev/demo-content")).andExpect(status().isNoContent());
    }

    @Test
    void theStatusIsIdleBeforeAnyFill() throws Exception {
        mockMvc.perform(get("/api/dev/demo-content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.startedAt").doesNotExist());
    }

    @Test
    void aFillAnswersAtOnceAndReportsItsProgressWhileOthersAreRefused() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Holds the fill in its first step, so the test can look at it mid-run.
        doAnswer(invocation -> {
            inside.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(wiper).wipe(false);

        mockMvc.perform(post("/api/dev/demo-content"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").exists());
        assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();

        mockMvc.perform(get("/api/dev/demo-content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.step").value("Removing earlier demo content"));
        mockMvc.perform(post("/api/dev/demo-content"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEV_DEMO_CONTENT_BUSY"));
        mockMvc.perform(delete("/api/dev/demo-content"))
                .andExpect(status().isConflict());

        release.countDown();
        JsonNode finished = awaitFinished();
        assertThat(finished.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(finished.get("result").get("articles").asInt()).isEqualTo(dataset.articles().size());
        assertThat(finished.get("finishedAt")).isNotNull();
    }

    @Test
    void aFailedFillReportsWhyAndFreesTheEndpoint() throws Exception {
        doThrow(new IllegalStateException("storage is unreachable")).when(wiper).wipe(false);

        mockMvc.perform(post("/api/dev/demo-content")).andExpect(status().isAccepted());
        JsonNode finished = awaitFinished();

        assertThat(finished.get("status").asString()).isEqualTo("FAILED");
        assertThat(finished.get("error").asString()).isEqualTo("storage is unreachable");
        assertThat(finished.get("result").isNull()).isTrue();
        // The busy flag was released: a removal is accepted, and it resets the status.
        reset(wiper);
        mockMvc.perform(delete("/api/dev/demo-content")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/dev/demo-content")).andExpect(jsonPath("$.status").value("IDLE"));
    }

    // Starts a fill and waits for it, failing the test unless it succeeds; returns what it created.
    private JsonNode seed() throws Exception {
        mockMvc.perform(post("/api/dev/demo-content"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        JsonNode finished = awaitFinished();
        assertThat(finished.get("status").asString()).as(finished.toString()).isEqualTo("SUCCEEDED");
        return finished.get("result");
    }

    // Polls the status route until no fill is running, and returns the final status.
    private JsonNode awaitFinished() {
        return await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(250))
                .until(this::currentStatus, current -> !"RUNNING".equals(current.get("status").asString()));
    }

    private JsonNode currentStatus() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/dev/demo-content")).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getData(String path, String language) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header(HttpHeaders.ACCEPT_LANGUAGE, language))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private Set<String> demoSlugs() {
        return articleRepository.findAll().stream()
                .filter(article -> DevDemoContent.MARKER.equals(article.getDevMarker()))
                .flatMap(article -> articleTranslationRepository.findByArticleId(article.getId()).stream())
                .map(ArticleTranslation::getSlug)
                .collect(Collectors.toSet());
    }

    private static JsonNode facet(JsonNode list, String key) {
        for (JsonNode facet : list.get("facets")) {
            if (key.equals(facet.get("key").asString())) {
                return facet;
            }
        }
        throw new AssertionError("no facet " + key);
    }

    private static JsonNode option(JsonNode facet, String code) {
        for (JsonNode option : facet.get("options")) {
            if (code.equals(option.get("code").asString())) {
                return option;
            }
        }
        throw new AssertionError("no option " + code + " in facet " + facet.get("key").asString());
    }
}
