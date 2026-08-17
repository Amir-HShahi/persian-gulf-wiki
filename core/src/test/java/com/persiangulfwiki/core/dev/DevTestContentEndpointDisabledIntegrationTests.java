package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.source.repository.SourceRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The counterpart to DevTestContentEndpointIntegrationTests, and the half that matters for
// review: no dev profile, therefore neither gate is in place, therefore the content fixture
// routes are not reachable. Same reasoning as DevTestUserEndpointDisabledIntegrationTests —
// see that class for the full explanation of the two gates.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DevTestContentEndpointDisabledIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void noContentFixtureBeanIsRegisteredOutsideTheDevProfile() {
        assertThat(applicationContext.getBeanNamesForType(DevTestSubjectController.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(DevTestSourceController.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(DevTestArticleController.class)).isEmpty();
        // The sweepers matter most of the six: they are the ones that delete rows, on a
        // schedule rather than on a request, so an unprofiled copy would run in production
        // with nothing to trigger it and nothing to notice.
        assertThat(applicationContext.getBeanNamesForType(DevTestSubjectSweeper.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(DevTestSourceSweeper.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(DevTestArticleSweeper.class)).isEmpty();
    }

    @Test
    void contentFixtureRoutesFallThroughToTheMainChainAndAreRejected() throws Exception {
        // Authorization runs before routing, so these are 401s rather than the 404 the
        // missing controllers would otherwise produce — the path never resolves to
        // "not mapped" at all.
        mockMvc.perform(get("/api/dev/test-subjects"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dev/test-sources/{sourceId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dev/test-articles/{articleId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mintingIsRejectedAndCreatesNothing() throws Exception {
        long subjectsBefore = subjectRepository.count();
        long sourcesBefore = sourceRepository.count();
        long articlesBefore = articleRepository.count();

        // 403 rather than 401 for the same reason as the user fixture route: the main chain's
        // CSRF filter runs ahead of authorization and /api/dev/** is not exempt from it, so
        // the missing header is what refuses the request first. Either status is a rejection;
        // what this pins down is that nothing was created.
        mockMvc.perform(post("/api/dev/test-subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/dev/test-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/dev/test-articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        assertThat(subjectRepository.count()).isEqualTo(subjectsBefore);
        assertThat(sourceRepository.count()).isEqualTo(sourcesBefore);
        assertThat(articleRepository.count()).isEqualTo(articlesBefore);
    }
}
