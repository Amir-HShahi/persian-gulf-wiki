package com.persiangulfwiki.core.dev;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.dev.dto.DevTestArticleRequest;
import com.persiangulfwiki.core.dev.dto.DevTestArticleResponse;
import com.persiangulfwiki.core.dev.dto.DevTestSourceRequest;
import com.persiangulfwiki.core.dev.dto.DevTestSubjectRequest;
import com.persiangulfwiki.core.source.entity.Source;
import com.persiangulfwiki.core.source.repository.SourceRepository;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.IslandRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs under the dev profile, the only profile in which the fixture controllers and
// DevSecurityConfig exist. Asserts on the rows it minted rather than on repository totals,
// since this context is shared with the other dev-profile suites.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevTestContentEndpointIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private IslandRepository islandRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ArticleTranslationRepository articleTranslationRepository;

    @Autowired
    private ArticleRevisionRepository articleRevisionRepository;

    @Autowired
    private DevTestSubjectSweeper subjectSweeper;

    @Autowired
    private DevTestSourceSweeper sourceSweeper;

    @Autowired
    private DevTestArticleSweeper articleSweeper;

    private UUID mintSubject(DevTestSubjectRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-subjects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.subjectId"));
    }

    private DevTestArticleResponse mintArticle(DevTestArticleRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, DevTestArticleResponse.class);
    }

    private UUID mintSource(DevTestSourceRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.sourceId"));
    }

    // No authentication of any kind — the whole point of the dev chain is that a harness can
    // call this before an account exists.
    @Test
    void mintsAMarkedSubjectWithItsDetailRow() throws Exception {
        UUID subjectId = mintSubject(new DevTestSubjectRequest(SubjectKind.ISLAND, null));

        Subject subject = subjectRepository.findById(subjectId).orElseThrow();
        assertThat(subject.getKind()).isEqualTo(SubjectKind.ISLAND);
        assertThat(subject.getDevMarker()).isNotNull();
        assertThat(islandRepository.findById(subjectId)).isPresent();
    }

    // The state the normal create path cannot produce, and the reason this endpoint bypasses
    // the service layer at all.
    @Test
    void mintsASubjectWithNoDetailRowAtAll() throws Exception {
        UUID subjectId = mintSubject(new DevTestSubjectRequest(SubjectKind.ISLAND, false));

        assertThat(subjectRepository.findById(subjectId)).isPresent();
        assertThat(islandRepository.findById(subjectId)).isEmpty();
    }

    @Test
    void deletesOnlySubjectsItMinted() throws Exception {
        UUID mintedId = mintSubject(new DevTestSubjectRequest(SubjectKind.PORT, null));

        mockMvc.perform(delete("/api/dev/test-subjects/" + mintedId))
                .andExpect(status().isNoContent());
        assertThat(subjectRepository.findById(mintedId)).isEmpty();

        // Deleting the same id again is a 404, not a second success.
        mockMvc.perform(delete("/api/dev/test-subjects/" + mintedId))
                .andExpect(status().isNotFound());
    }

    // The guarantee that makes handing an arbitrary id to the teardown route safe: a subject
    // that this endpoint did not create is refused even though the id is perfectly valid.
    @Test
    void refusesToDeleteASubjectItDidNotMint() throws Exception {
        Subject handMade = subjectRepository.save(Subject.builder().kind(SubjectKind.SPECIES).build());

        mockMvc.perform(delete("/api/dev/test-subjects/" + handMade.getId()))
                .andExpect(status().isNotFound());
        assertThat(subjectRepository.findById(handMade.getId())).isPresent();
    }

    @Test
    void mintsAndDeletesAMarkedSource() throws Exception {
        UUID sourceId = mintSource(new DevTestSourceRequest(null, null));

        Source source = sourceRepository.findById(sourceId).orElseThrow();
        assertThat(source.getDevMarker()).isNotNull();
        assertThat(source.getTitle()).isNotBlank();

        mockMvc.perform(delete("/api/dev/test-sources/" + sourceId))
                .andExpect(status().isNoContent());
        assertThat(sourceRepository.findById(sourceId)).isEmpty();
    }

    @Test
    void refusesToDeleteASourceItDidNotMint() throws Exception {
        Source handMade = sourceRepository.save(Source.builder().title("A real citation").build());

        mockMvc.perform(delete("/api/dev/test-sources/" + handMade.getId()))
                .andExpect(status().isNotFound());
        assertThat(sourceRepository.findById(handMade.getId())).isPresent();
    }

    // A future threshold makes every row "expired", which is what lets this assert the
    // sweeper's selectivity without waiting out a real TTL.
    @Test
    void sweeperReclaimsMintedRowsAndSparesHandMadeOnes() throws Exception {
        UUID mintedSubjectId = mintSubject(new DevTestSubjectRequest(SubjectKind.OIL_FIELD, null));
        UUID mintedSourceId = mintSource(new DevTestSourceRequest(null, null));
        Subject handMadeSubject = subjectRepository.save(Subject.builder().kind(SubjectKind.PORT).build());
        Source handMadeSource = sourceRepository.save(Source.builder().title("Survives the sweep").build());

        Instant threshold = Instant.now().plus(1, ChronoUnit.HOURS);
        subjectSweeper.deleteOlderThan(threshold);
        sourceSweeper.deleteOlderThan(threshold);

        assertThat(subjectRepository.findById(mintedSubjectId)).isEmpty();
        assertThat(sourceRepository.findById(mintedSourceId)).isEmpty();
        assertThat(subjectRepository.findById(handMadeSubject.getId())).isPresent();
        assertThat(sourceRepository.findById(handMadeSource.getId())).isPresent();
    }

    @Test
    void mintsAMarkedArticleWithItsTranslationAndFirstRevision() throws Exception {
        DevTestArticleResponse minted = mintArticle(
                new DevTestArticleRequest(null, null, null, null, null, null, null, null, null));

        Article article = articleRepository.findById(minted.articleId()).orElseThrow();
        assertThat(article.getDevMarker()).isNotNull();
        assertThat(article.getEntityType()).isEqualTo(EntityType.GENERIC);

        ArticleTranslation translation = articleTranslationRepository.findById(minted.translationId()).orElseThrow();
        assertThat(translation.getArticleId()).isEqualTo(article.getId());
        assertThat(translation.getCurrentRevisionId()).isEqualTo(minted.revisionId());

        ArticleRevision revision = articleRevisionRepository.findById(minted.revisionId()).orElseThrow();
        assertThat(revision.getStatus()).isEqualTo(RevisionStatus.DRAFT);
        // No authorUserId was supplied -- the endpoint must have resolved a real one rather
        // than leaving the NOT NULL author_id column empty or dangling.
        assertThat(revision.getAuthorId()).isNotNull();
    }

    // The state the normal submit/review flow cannot produce, and the reason this endpoint
    // bypasses ArticleRevisionService at all: DRAFT -> PENDING -> a moderator's decision is
    // the only path in the real API, so an APPROVED revision with zero prior history is
    // otherwise unreachable.
    @Test
    void mintsARevisionAtApprovedWithNoModerationHistory() throws Exception {
        DevTestArticleResponse minted = mintArticle(new DevTestArticleRequest(
                null, null, null, null, RevisionStatus.APPROVED, null, null, null, null));

        ArticleRevision revision = articleRevisionRepository.findById(minted.revisionId()).orElseThrow();
        assertThat(revision.getStatus()).isEqualTo(RevisionStatus.APPROVED);
    }

    @Test
    void deletesAMintedArticleAndCascadesToItsTranslationAndRevision() throws Exception {
        DevTestArticleResponse minted = mintArticle(
                new DevTestArticleRequest(null, null, null, null, null, null, null, null, null));

        mockMvc.perform(delete("/api/dev/test-articles/" + minted.articleId()))
                .andExpect(status().isNoContent());

        assertThat(articleRepository.findById(minted.articleId())).isEmpty();
        assertThat(articleTranslationRepository.findById(minted.translationId())).isEmpty();
        assertThat(articleRevisionRepository.findById(minted.revisionId())).isEmpty();

        // Deleting the same id again is a 404, not a second success.
        mockMvc.perform(delete("/api/dev/test-articles/" + minted.articleId()))
                .andExpect(status().isNotFound());
    }

    // The guarantee that makes handing an arbitrary id to the teardown route safe: an article
    // that this endpoint did not create is refused even though the id is perfectly valid.
    @Test
    void refusesToDeleteAnArticleItDidNotMint() throws Exception {
        Article handMade = articleRepository.save(
                Article.builder().entityType(EntityType.GENERIC).canonicalLanguage("fa").build());

        mockMvc.perform(delete("/api/dev/test-articles/" + handMade.getId()))
                .andExpect(status().isNotFound());
        assertThat(articleRepository.findById(handMade.getId())).isPresent();
    }

    @Test
    void sweeperReclaimsAgedMintedArticlesButSparesHandMadeOnes() throws Exception {
        DevTestArticleResponse minted = mintArticle(
                new DevTestArticleRequest(null, null, null, null, null, null, null, null, null));
        Article handMade = articleRepository.save(
                Article.builder().entityType(EntityType.GENERIC).canonicalLanguage("fa").build());

        Instant threshold = Instant.now().plus(1, ChronoUnit.HOURS);
        articleSweeper.deleteOlderThan(threshold);

        assertThat(articleRepository.findById(minted.articleId())).isEmpty();
        assertThat(articleRepository.findById(handMade.getId())).isPresent();
    }
}
