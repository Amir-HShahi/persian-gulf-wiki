package com.persiangulfwiki.core.search;

import tools.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.dto.CreateArticleRequest;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.persiangulfwiki.core.CsrfTestSupport.xsrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Article search against the worker's REAL search schema: the test applies
// submission-pipeline/internal/search/schema.sql to its Postgres container -- exactly what the
// worker does on start -- and runs the worker's search_reconcile() after publishing articles, in
// place of the worker's 30-second sync. Nothing here runs the worker itself.
//
// The rate limits are lowered (and the window made an hour, so a run never straddles two) to
// reach a 429 in a few requests; every request carries a CF-Connecting-IP of its own unless a test
// sets one, so the tests never share a budget.
//
// Structured like the other flow tests: helpers, then the headline flow (smoke), then each
// feature of the query, then validation, then the rate limit, then regression on the article
// list the index is built from.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.search.searches-per-window=5",
        "app.search.suggestions-per-window=7",
        "app.search.rate-window-seconds=3600"})
class SearchFlowIntegrationTests {

    private static final String PASSWORD = "Correct-Horse1!";
    // Relative to core/, where Maven runs the tests.
    private static final Path WORKER_SEARCH_SCHEMA = Path.of("..", "submission-pipeline", "internal", "search",
            "schema.sql");
    private static final Random RANDOM = new Random();
    private static final String LATIN = "bcdfghjklmnpqrstvwxz";
    private static final String PERSIAN = "بپتجچحخدذرزژسشصضطظعغفقگلمنه";

    // Once per context: the file is idempotent, but re-running it for every test is slow.
    private static boolean isSchemaApplied;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private ArticleRevisionService articleRevisionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Cookie authorCookie;

    @BeforeEach
    void setUp() throws Exception {
        if (!isSchemaApplied) {
            // One statement: the file's function bodies are dollar-quoted, and the driver splits
            // a multi-statement string itself, honouring those quotes.
            jdbcTemplate.execute(Files.readString(WORKER_SEARCH_SCHEMA, StandardCharsets.UTF_8));
            isSchemaApplied = true;
        }
        authorCookie = verifiedAccount();
    }

    // --- Helpers ---

    private Cookie verifiedAccount() throws Exception {
        String slug = UUID.randomUUID().toString().substring(0, 8);
        String email = "search-" + slug + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest("srch_" + slug, email, PASSWORD))))
                .andExpect(status().isCreated());
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);
        return mockMvc.perform(post("/api/auth/login")
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    // A word no other test's article contains, so each test sees only its own rows in an index
    // the whole class shares.
    private static String token(String alphabet, int length) {
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < length; i++) {
            word.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return word.toString();
    }

    private static String latinToken() {
        return token(LATIN, 10);
    }

    // Created through the real route; returns {articleId, revisionId}.
    private UUID[] createArticle(@Nullable UUID subjectId, String language, String title, String bodyText)
            throws Exception {
        CreateArticleRequest request = new CreateArticleRequest(subjectId, language, "search-" + UUID.randomUUID(),
                title, objectMapper.readTree(objectMapper.writeValueAsString(Map.of("text", bodyText))),
                null, null);
        String created = mockMvc.perform(post("/api/articles")
                        .cookie(authorCookie)
                        .with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID articleId = UUID.fromString(JsonPath.read(created, "$.data.id"));
        String revisions = mockMvc.perform(get("/api/articles/" + articleId + "/translations/" + language + "/revisions")
                        .cookie(authorCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new UUID[] {articleId, UUID.fromString(JsonPath.read(revisions, "$.data[0].id"))};
    }

    // Moderation's own approval step, called directly: these tests are about what search does
    // with a published translation, and ModerationFlowIntegrationTests covers how one gets there.
    private UUID publish(@Nullable UUID subjectId, String language, String title, String bodyText) throws Exception {
        UUID[] created = createArticle(subjectId, language, title, bodyText);
        articleRevisionService.applyModerationOutcome(created[1], RevisionStatus.APPROVED);
        return created[0];
    }

    // The worker's sync, run now instead of within 30 seconds.
    private void reconcile() {
        jdbcTemplate.queryForList("select * from search_reconcile(500)");
    }

    private static String randomIpv4() {
        return "198.18." + RANDOM.nextInt(256) + "." + (1 + RANDOM.nextInt(254));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String clientIp) throws Exception {
        return mockMvc.perform(request.header("CF-Connecting-IP", clientIp));
    }

    private ResultActions search(String... params) throws Exception {
        return perform(withParams(get("/api/search"), params), randomIpv4());
    }

    private ResultActions suggest(String... params) throws Exception {
        return perform(withParams(get("/api/search/suggestions"), params), randomIpv4());
    }

    private static MockHttpServletRequestBuilder withParams(MockHttpServletRequestBuilder request, String... params) {
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return request;
    }

    // --- Smoke: the headline flow ---

    @Test
    void publishedArticlesAreFoundAnonymouslyWithTheirSnippetAndTotalAndDraftsAreNot() throws Exception {
        String word = latinToken();
        UUID first = publish(null, "en", "Lighthouse " + word, "The old keeper of the " + word + " light.");
        UUID second = publish(null, "en", "Harbour notes", "Boats leave at dawn past " + word + " point.");
        createArticle(null, "en", "Draft about " + word, "Never approved.");
        reconcile();

        String body = search("q", word)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.didYouMean").value(nullValue()))
                // The title match ranks above the body match.
                .andExpect(jsonPath("$.data.items[0].articleId").value(first.toString()))
                .andExpect(jsonPath("$.data.items[0].language").value("en"))
                .andExpect(jsonPath("$.data.items[0].title").value("Lighthouse " + word))
                .andExpect(jsonPath("$.data.items[0].subjectKind").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].translationId").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].slug").isNotEmpty())
                .andExpect(jsonPath("$.data.items[1].articleId").value(second.toString()))
                .andExpect(jsonPath("$.data.items[1].snippet", containsString("<mark>")))
                .andReturn().getResponse().getContentAsString();
        List<String> titles = JsonPath.read(body, "$.data.items[*].title");
        assertThat(titles).doesNotContain("Draft about " + word);

        // The language filter: these are English articles.
        search("q", word, "language", "fa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        search("q", word, "language", "en").andExpect(jsonPath("$.data.total").value(2));

        // Suggestions while typing: the title starts with what was typed, word by word.
        suggest("prefix", "lighthouse " + word.substring(0, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].articleId").value(first.toString()))
                .andExpect(jsonPath("$.data[0].title").value("Lighthouse " + word))
                .andExpect(jsonPath("$.data[0].language").value("en"));
    }

    // --- What the query matches ---

    // A reader typing Arabic letter forms finds the Persian spelling, and the reverse.
    @Test
    void persianAndArabicLetterFormsMatchEachOther() throws Exception {
        String word = "ک" + token(PERSIAN, 6) + "ی";
        UUID article = publish(null, "fa", "جزیره " + word, "متن");
        reconcile();

        String arabicForms = word.replace('ک', 'ك').replace('ی', 'ي');
        search("q", arabicForms)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].articleId").value(article.toString()))
                .andExpect(jsonPath("$.data.items[0].language").value("fa"));
        suggest("prefix", arabicForms.substring(0, 4), "language", "fa")
                .andExpect(jsonPath("$.data[0].articleId").value(article.toString()));
        suggest("prefix", arabicForms.substring(0, 4), "language", "ar")
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // Past the last page the function returns no rows, so no total; core recovers it.
    @Test
    void pagesCarryTheFullTotalEvenPastTheLastPage() throws Exception {
        String word = latinToken();
        for (int i = 0; i < 3; i++) {
            publish(null, "en", "Island " + word + " " + i, "Body " + i);
        }
        reconcile();

        search("q", word, "size", "2")
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                // Three or more in all: nothing to correct, so not even asked.
                .andExpect(jsonPath("$.data.didYouMean").value(nullValue()));
        search("q", word, "size", "2", "page", "1")
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items", hasSize(1)));
        search("q", word, "size", "2", "page", "7")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    // Case-insensitive, and articles with no subject never match a kind.
    @Test
    void theSubjectKindFilterKeepsOnlyArticlesAboutThatKindOfSubject() throws Exception {
        String word = latinToken();
        UUID island = subjectRepository.save(Subject.builder().kind(SubjectKind.ISLAND).build()).getId();
        UUID onIsland = publish(island, "en", "Shore " + word, "Sand.");
        publish(null, "en", "Shore " + word + " unbound", "Rock.");
        reconcile();

        search("q", word, "subjectKind", "island")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].articleId").value(onIsland.toString()))
                .andExpect(jsonPath("$.data.items[0].subjectKind").value("ISLAND"));
        search("q", word, "subjectKind", "PORT")
                .andExpect(jsonPath("$.data.total").value(0));
        search("q", word).andExpect(jsonPath("$.data.total").value(2));
    }

    // Few results: the worker's vocabulary offers the closest word an article does contain.
    @Test
    void aMisspeltQueryWithFewResultsOffersACorrection() throws Exception {
        String word = latinToken();
        publish(null, "en", "Lighthouse " + word, "Keeper.");
        reconcile();

        String typo = word.substring(0, word.length() - 1) + word.charAt(word.length() - 1)
                + word.charAt(word.length() - 1);
        search("q", typo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.didYouMean", equalToIgnoringCase(word)));
        // A word the index knows is not a typo.
        search("q", word).andExpect(jsonPath("$.data.didYouMean").value(nullValue()));
    }

    @Test
    void aQueryMatchingNothingIsAnEmptyPageNotAnError() throws Exception {
        search("q", latinToken() + latinToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        suggest("prefix", latinToken() + latinToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void suggestionsAreCappedByLimit() throws Exception {
        String word = latinToken();
        for (int i = 0; i < 3; i++) {
            publish(null, "en", word + " cove " + i, "Body.");
        }
        reconcile();

        suggest("prefix", word).andExpect(jsonPath("$.data", hasSize(3)));
        suggest("prefix", word, "limit", "2")
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].title", everyItem(containsString(word))));
    }

    // --- Validation ---

    @Test
    void searchParametersOutOfRangeAreA400() throws Exception {
        search().andExpect(status().isBadRequest());
        search("q", "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        search("q", "a".repeat(201)).andExpect(status().isBadRequest());
        search("q", "a".repeat(200)).andExpect(status().isOk());
        search("q", "kish", "language", "de").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        search("q", "kish", "language", "FA").andExpect(status().isBadRequest());
        search("q", "kish", "page", "-1").andExpect(status().isBadRequest());
        search("q", "kish", "page", "501").andExpect(status().isBadRequest());
        search("q", "kish", "page", "500").andExpect(status().isOk());
        search("q", "kish", "page", "x").andExpect(status().isBadRequest());
        search("q", "kish", "size", "0").andExpect(status().isBadRequest());
        search("q", "kish", "size", "51").andExpect(status().isBadRequest());
        search("q", "kish", "size", "50").andExpect(status().isOk());
        search("q", "kish", "subjectKind", "reef")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUBJECT_KIND"));
    }

    @Test
    void suggestionParametersOutOfRangeAreA400() throws Exception {
        suggest().andExpect(status().isBadRequest());
        suggest("prefix", " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        suggest("prefix", "a".repeat(101)).andExpect(status().isBadRequest());
        suggest("prefix", "a".repeat(100)).andExpect(status().isOk());
        suggest("prefix", "ki", "language", "tr").andExpect(status().isBadRequest());
        suggest("prefix", "ki", "limit", "0").andExpect(status().isBadRequest());
        suggest("prefix", "ki", "limit", "21").andExpect(status().isBadRequest());
        suggest("prefix", "ki", "limit", "20").andExpect(status().isOk());
    }

    // --- The rate limit ---

    @Test
    void searchesPastTheLimitAreA429WithRetryAfterForThatClientOnly() throws Exception {
        String client = randomIpv4();
        for (int i = 0; i < 5; i++) {
            perform(get("/api/search").param("q", "kish"), client).andExpect(status().isOk());
        }

        String retryAfter = perform(get("/api/search").param("q", "kish"), client)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SEARCH_RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"))
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 3600L);

        // Another address has its own budget, and the same address still has its suggestions.
        perform(get("/api/search").param("q", "kish"), randomIpv4()).andExpect(status().isOk());
        perform(get("/api/search/suggestions").param("prefix", "ki"), client).andExpect(status().isOk());
        // The limit is checked before the subject kind is parsed, so a bad filter is counted too.
        perform(get("/api/search").param("q", "kish").param("subjectKind", "reef"), client)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void suggestionsHaveTheirOwnLargerBudget() throws Exception {
        String client = randomIpv4();
        for (int i = 0; i < 7; i++) {
            perform(get("/api/search/suggestions").param("prefix", "ki"), client).andExpect(status().isOk());
        }

        perform(get("/api/search/suggestions").param("prefix", "ki"), client)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SEARCH_RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        perform(get("/api/search").param("q", "kish"), client).andExpect(status().isOk());
    }

    // One subscriber's /64 is one client: rotating through its addresses does not reset the
    // limit, while the neighbouring /64 is somebody else.
    @Test
    void ipv6ClientsShareTheBudgetOfTheirSlash64() throws Exception {
        String prefix = "2001:db8:" + Integer.toHexString(RANDOM.nextInt(0xffff)) + ":"
                + Integer.toHexString(RANDOM.nextInt(0xffff));
        for (int i = 1; i <= 5; i++) {
            perform(get("/api/search").param("q", "kish"), prefix + "::" + Integer.toHexString(i))
                    .andExpect(status().isOk());
        }
        perform(get("/api/search").param("q", "kish"), prefix + ":ffff:ffff:ffff:ffff")
                .andExpect(status().isTooManyRequests());
        perform(get("/api/search").param("q", "kish"), "2001:db8:ffff:" + Integer.toHexString(RANDOM.nextInt(0xfff))
                + "::1").andExpect(status().isOk());
    }

    // A header that is no IP literal is ignored, never resolved: the request is counted against
    // the socket address instead, which every such request in this test shares.
    @Test
    void aCloudflareHeaderThatIsNoIpLiteralFallsBackToTheSocketAddress() throws Exception {
        String socket = "203.0.113." + (1 + RANDOM.nextInt(254));
        for (int i = 0; i < 5; i++) {
            String header = i % 2 == 0 ? "localhost" : "not-an-address-" + i;
            mockMvc.perform(get("/api/search").param("q", "kish").header("CF-Connecting-IP", header)
                            .with(request -> {
                                request.setRemoteAddr(socket);
                                return request;
                            }))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/search").param("q", "kish").with(request -> {
                    request.setRemoteAddr(socket);
                    return request;
                }))
                .andExpect(status().isTooManyRequests());
    }

    // --- Regression: the source of the index ---

    // Search is a second reader of the published articles; the article list itself, and its
    // anonymous access, are unchanged by it.
    @Test
    void theAnonymousArticleListStillListsWhatSearchFinds() throws Exception {
        String word = latinToken();
        UUID island = subjectRepository.save(Subject.builder().kind(SubjectKind.ISLAND).build()).getId();
        UUID article = publish(island, "en", "Reef " + word, "Coral.");
        reconcile();

        mockMvc.perform(get("/api/articles").param("language", "en").param("subjectId", island.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(article.toString()));
        search("q", word).andExpect(jsonPath("$.data.items[0].articleId").value(article.toString()));
    }
}
