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
import com.persiangulfwiki.core.dev.dto.DevTestMediaRequest;
import com.persiangulfwiki.core.dev.dto.DevTestMediaResponse;
import com.persiangulfwiki.core.dev.dto.DevTestModerationRequest;
import com.persiangulfwiki.core.dev.dto.DevTestModerationResponse;
import com.persiangulfwiki.core.dev.dto.DevTestSourceRequest;
import com.persiangulfwiki.core.dev.dto.DevTestSubjectRequest;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.entity.ModerationDecision;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.repository.PanoramaLinkRepository;
import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.repository.ModerationDecisionRepository;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @Autowired
    private ModerationTaskRepository moderationTaskRepository;

    @Autowired
    private ModerationDecisionRepository moderationDecisionRepository;

    @Autowired
    private DevTestModerationSweeper moderationSweeper;

    @Autowired
    private ArticleMediaRepository articleMediaRepository;

    @Autowired
    private MediaMetadataVersionRepository mediaMetadataVersionRepository;

    @Autowired
    private PanoramaLinkRepository panoramaLinkRepository;

    @Autowired
    private DevTestMediaSweeper mediaSweeper;

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

    private DevTestModerationResponse mintModerationTask(DevTestModerationRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-moderation-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, DevTestModerationResponse.class);
    }

    // At most one task can ever exist per revision (uq_moderation_tasks_revision), so every
    // moderation case below needs a revision of its own rather than a shared fixture.
    private UUID mintRevisionId() throws Exception {
        return mintArticle(new DevTestArticleRequest(null, null, null, null, null, null, null, null, null))
                .revisionId();
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
        // Minted at DRAFT, so nothing is published -- the fixture mirrors the production
        // invariant that only an APPROVED revision may be a translation's currentRevisionId.
        assertThat(translation.getCurrentRevisionId()).isNull();

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

    @Test
    void mintsAMarkedModerationTaskOpenAndUnclaimed() throws Exception {
        UUID revisionId = mintRevisionId();

        DevTestModerationResponse minted =
                mintModerationTask(new DevTestModerationRequest(revisionId, null, null, null, null, null));

        ModerationTask task = moderationTaskRepository.findById(minted.taskId()).orElseThrow();
        assertThat(task.getDevMarker()).isNotNull();
        assertThat(task.getRevisionId()).isEqualTo(revisionId);
        assertThat(task.getState()).isEqualTo(ModerationTaskState.OPEN);
        // The claim pair is written together or not at all, and an open task has no holder.
        assertThat(task.getClaimedBy()).isNull();
        assertThat(task.getClaimedAt()).isNull();
        assertThat(moderationDecisionRepository.findByTaskIdOrderByCreatedAtAsc(task.getId())).isEmpty();
    }

    // The first of the two states the normal flow cannot hand a suite cheaply: reaching CLAIMED
    // through the API needs an authenticated moderator account plus a claim call.
    @Test
    void mintsAClaimedTaskWithBothClaimColumnsSet() throws Exception {
        DevTestModerationResponse minted = mintModerationTask(
                new DevTestModerationRequest(mintRevisionId(), ModerationTaskState.CLAIMED, null, null, null, null));

        ModerationTask task = moderationTaskRepository.findById(minted.taskId()).orElseThrow();
        assertThat(task.getState()).isEqualTo(ModerationTaskState.CLAIMED);
        // No claimant was supplied -- the endpoint must have resolved a real one rather than
        // leaving a row that violates the claim-pair constraint or that nobody can decide.
        assertThat(task.getClaimedBy()).isNotNull();
        assertThat(task.getClaimedAt()).isNotNull();
        assertThat(minted.claimedByUserId()).isEqualTo(task.getClaimedBy());
    }

    // The state the normal flow cannot produce at all, and the reason this endpoint bypasses
    // ModerationService: decide() refuses a revision that is not PENDING, so a DECIDED task
    // whose revision never went through submit is otherwise unreachable.
    @Test
    void mintsADecidedTaskWithASeededDecisionOnItsHistory() throws Exception {
        DevTestModerationResponse minted = mintModerationTask(new DevTestModerationRequest(
                mintRevisionId(), ModerationTaskState.DECIDED, null, Decision.REJECT, "out of scope", null));

        ModerationTask task = moderationTaskRepository.findById(minted.taskId()).orElseThrow();
        assertThat(task.getState()).isEqualTo(ModerationTaskState.DECIDED);

        assertThat(minted.decisionId()).isNotNull();
        ModerationDecision decision =
                moderationDecisionRepository.findById(minted.decisionId()).orElseThrow();
        assertThat(decision.getTaskId()).isEqualTo(task.getId());
        assertThat(decision.getDecision()).isEqualTo(Decision.REJECT);
        assertThat(decision.getReason()).isEqualTo("out of scope");
        // moderator_id is NOT NULL with RESTRICT -- a fabricated id would have failed the
        // foreign key, so a real account must have been resolved.
        assertThat(decision.getModeratorId()).isNotNull();
    }

    // A task has to point at something. The foreign key would catch this too, but as a 500 --
    // the endpoint checks first so a suite gets a 404 it can assert on.
    @Test
    void refusesToMintAModerationTaskForAnUnknownRevision() throws Exception {
        mockMvc.perform(post("/api/dev/test-moderation-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DevTestModerationRequest(UUID.randomUUID(), null, null, null, null, null))))
                .andExpect(status().isNotFound());

    }

    // The opposite of the refusal above: naming no target at all mints a marked, PENDING article
    // to judge, so a moderation suite needs no setup call.
    @Test
    void mintsAModerationTaskOnAThrowawayArticleWhenNoTargetIsSent() throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-moderation-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID taskId = UUID.fromString(objectMapper.readTree(body).get("taskId").asText());
        UUID revisionId = UUID.fromString(objectMapper.readTree(body).get("revisionId").asText());

        assertThat(articleRevisionRepository.findById(revisionId)).get()
                .extracting(revision -> revision.getStatus()).isEqualTo(RevisionStatus.PENDING);
        assertThat(moderationTaskRepository.findById(taskId)).get()
                .extracting(task -> task.getRevisionId()).isEqualTo(revisionId);
        mockMvc.perform(delete("/api/dev/test-moderation-tasks/" + taskId)).andExpect(status().isNoContent());
    }

    @Test
    void deletesAMintedModerationTaskAndCascadesToItsDecision() throws Exception {
        DevTestModerationResponse minted = mintModerationTask(new DevTestModerationRequest(
                mintRevisionId(), ModerationTaskState.DECIDED, null, Decision.APPROVE, null, null));

        mockMvc.perform(delete("/api/dev/test-moderation-tasks/" + minted.taskId()))
                .andExpect(status().isNoContent());

        assertThat(moderationTaskRepository.findById(minted.taskId())).isEmpty();
        assertThat(moderationDecisionRepository.findById(minted.decisionId())).isEmpty();

        // Deleting the same id again is a 404, not a second success.
        mockMvc.perform(delete("/api/dev/test-moderation-tasks/" + minted.taskId()))
                .andExpect(status().isNotFound());
    }

    // The guarantee that makes handing an arbitrary id to the teardown route safe, and it
    // matters more here than for the other fixture tables: a task is the record that an
    // editorial judgement was asked for, so deleting one the endpoint did not mint would erase
    // audit history.
    @Test
    void refusesToDeleteAModerationTaskItDidNotMint() throws Exception {
        ModerationTask handMade = moderationTaskRepository.save(ModerationTask.builder()
                .revisionId(mintRevisionId())
                .state(ModerationTaskState.OPEN)
                .build());

        mockMvc.perform(delete("/api/dev/test-moderation-tasks/" + handMade.getId()))
                .andExpect(status().isNotFound());
        assertThat(moderationTaskRepository.findById(handMade.getId())).isPresent();
    }

    @Test
    void sweeperReclaimsAgedMintedModerationTasksButSparesHandMadeOnes() throws Exception {
        DevTestModerationResponse minted =
                mintModerationTask(new DevTestModerationRequest(mintRevisionId(), null, null, null, null, null));
        ModerationTask handMade = moderationTaskRepository.save(ModerationTask.builder()
                .revisionId(mintRevisionId())
                .state(ModerationTaskState.OPEN)
                .build());

        moderationSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(moderationTaskRepository.findById(minted.taskId())).isEmpty();
        assertThat(moderationTaskRepository.findById(handMade.getId())).isPresent();
    }

    // Regression guard on the adjacent half: the moderation fixture shares the dev chain and
    // the dev-marker convention with the endpoints that were already here, so this pins down
    // that adding it did not disturb them.
    @Test
    void existingFixtureRoutesStillWorkAlongsideTheModerationOne() throws Exception {
        UUID subjectId = mintSubject(new DevTestSubjectRequest(SubjectKind.ISLAND, null));
        UUID sourceId = mintSource(new DevTestSourceRequest(null, null));
        DevTestArticleResponse article = mintArticle(
                new DevTestArticleRequest(null, null, null, null, null, null, null, null, null));

        assertThat(subjectRepository.findById(subjectId)).isPresent();
        assertThat(sourceRepository.findById(sourceId)).isPresent();
        assertThat(articleRepository.findById(article.articleId())).isPresent();

        mockMvc.perform(delete("/api/dev/test-subjects/" + subjectId)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/dev/test-sources/" + sourceId)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/dev/test-articles/" + article.articleId())).andExpect(status().isNoContent());
    }

    // --- Media fixtures ---

    private DevTestMediaResponse mintMedia(DevTestMediaRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-media")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, DevTestMediaResponse.class);
    }

    private UUID mintArticleId() throws Exception {
        return mintArticle(new DevTestArticleRequest(null, null, null, null, null, null, null, null, null)).articleId();
    }

    private DevTestMediaRequest mediaRequest(UUID articleId, ProcessingStatus processing, PublicationStatus publication) {
        return new DevTestMediaRequest(articleId, null, processing, publication, null, null, null, null);
    }

    // Every state the gallery defines, including the ones only the pipeline or a moderator can
    // reach, each with its first metadata version where the real flow would have left it.
    @Test
    void mintsAMarkedMediaItemInEveryStateWithTheImpliedFirstMetadataVersion() throws Exception {
        UUID articleId = mintArticleId();
        record Case(ProcessingStatus processing, PublicationStatus publication, MetadataVersionStatus firstVersion) {
        }
        List<Case> cases = List.of(
                new Case(ProcessingStatus.UPLOADING, PublicationStatus.PENDING, MetadataVersionStatus.PENDING_REVIEW),
                new Case(ProcessingStatus.PROCESSING, PublicationStatus.PENDING, MetadataVersionStatus.PENDING_REVIEW),
                new Case(ProcessingStatus.READY, PublicationStatus.PENDING, MetadataVersionStatus.PENDING_REVIEW),
                new Case(ProcessingStatus.READY, PublicationStatus.PUBLISHED, MetadataVersionStatus.APPROVED),
                new Case(ProcessingStatus.READY, PublicationStatus.REJECTED, MetadataVersionStatus.REJECTED),
                new Case(ProcessingStatus.FAILED, PublicationStatus.PENDING, MetadataVersionStatus.PENDING_REVIEW),
                new Case(ProcessingStatus.READY, PublicationStatus.HIDDEN, MetadataVersionStatus.APPROVED));

        for (Case c : cases) {
            DevTestMediaResponse minted = mintMedia(mediaRequest(articleId, c.processing(), c.publication()));
            ArticleMedia media = articleMediaRepository.findById(minted.mediaId()).orElseThrow();
            assertThat(media.getDevMarker()).isEqualTo(DevTestFixtures.MARKER);
            assertThat(media.getProcessingStatus()).isEqualTo(c.processing());
            assertThat(media.getPublicationStatus()).isEqualTo(c.publication());
            assertThat(mediaMetadataVersionRepository.findById(minted.firstMetadataVersionId()).orElseThrow().getStatus())
                    .isEqualTo(c.firstVersion());
            boolean approved = c.firstVersion() == MetadataVersionStatus.APPROVED;
            assertThat(media.getCurrentMetadataVersionId()).isEqualTo(approved ? minted.firstMetadataVersionId() : null);
            assertThat(media.getVariants()).hasSize(c.processing() == ProcessingStatus.READY ? 1 : 0);
            assertThat(media.getFailureCode()).isEqualTo(c.processing() == ProcessingStatus.FAILED ? "upload_mismatch" : null);
        }
    }

    @Test
    void mintsLaterMetadataVersionsInAnyStatusAndPanoramaLinksOnAnyVersion() throws Exception {
        UUID articleId = mintArticleId();
        DevTestMediaResponse target = mintMedia(new DevTestMediaRequest(articleId, MediaKind.PANORAMA_360,
                ProcessingStatus.READY, PublicationStatus.PUBLISHED, null, null, null, null));
        // v2 approved (current), v3 rejected, v4 and v5 pending -- links on the newest by default.
        DevTestMediaResponse source = mintMedia(new DevTestMediaRequest(articleId, MediaKind.PANORAMA_360,
                ProcessingStatus.READY, PublicationStatus.PUBLISHED, null,
                List.of(MetadataVersionStatus.APPROVED, MetadataVersionStatus.REJECTED,
                        MetadataVersionStatus.PENDING_REVIEW, MetadataVersionStatus.PENDING_REVIEW),
                List.of(target.mediaId()), null));

        assertThat(source.laterMetadataVersionIds()).hasSize(4);
        assertThat(source.laterMetadataVersionIds().stream()
                .map(id -> mediaMetadataVersionRepository.findById(id).orElseThrow())
                .map(version -> version.getVersionNumber() + ":" + version.getStatus()))
                .containsExactly("2:APPROVED", "3:REJECTED", "4:PENDING_REVIEW", "5:PENDING_REVIEW");
        UUID approvedEdit = source.laterMetadataVersionIds().getFirst();
        assertThat(source.currentMetadataVersionId()).isEqualTo(approvedEdit);
        assertThat(articleMediaRepository.findById(source.mediaId()).orElseThrow().getCurrentMetadataVersionId())
                .isEqualTo(approvedEdit);
        assertThat(source.panoramaLinkIds()).hasSize(1);
        assertThat(panoramaLinkRepository.findByMetadataVersionId(source.laterMetadataVersionIds().getLast()))
                .singleElement()
                .satisfies(link -> assertThat(link.getToMediaId()).isEqualTo(target.mediaId()));

        // Links on a chosen version, here the first.
        DevTestMediaResponse onFirst = mintMedia(new DevTestMediaRequest(articleId, MediaKind.PANORAMA_360,
                ProcessingStatus.READY, PublicationStatus.PUBLISHED, null, List.of(MetadataVersionStatus.PENDING_REVIEW),
                List.of(target.mediaId()), 1));
        assertThat(panoramaLinkRepository.findByMetadataVersionId(onFirst.firstMetadataVersionId())).hasSize(1);
        assertThat(onFirst.currentMetadataVersionId()).isEqualTo(onFirst.firstMetadataVersionId());

        // An approved edit on an item never approved is a state nothing reaches: refused.
        mockMvc.perform(post("/api/dev/test-media")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DevTestMediaRequest(articleId, null,
                                ProcessingStatus.READY, PublicationStatus.PENDING, null,
                                List.of(MetadataVersionStatus.APPROVED), null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEV_TEST_MEDIA_REQUEST"));
        mockMvc.perform(post("/api/dev/test-media")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DevTestMediaRequest(articleId, null, null, null,
                                null, null, List.of(target.mediaId()), 2))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/dev/test-media")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DevTestMediaRequest(articleId, null, null, null,
                                null, null, List.of(UUID.randomUUID()), null))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/dev/test-media")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DevTestMediaRequest(UUID.randomUUID(), null,
                                null, null, null, null, null, null))))
                .andExpect(status().isNotFound());
        // A blank id in the list ("" from an API client's template body) reads as null: 400, not a 500.
        mockMvc.perform(post("/api/dev/test-media")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"articleId\":\"" + articleId + "\",\"panoramaLinkTargetIds\":[\"\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEV_TEST_MEDIA_REQUEST"));
    }

    // No articleId: the fixture mints its own marked, APPROVED article, and the item is minted
    // on it. Sending an id still targets that article (covered above).
    @Test
    void mintsMediaOnAThrowawayArticleWhenNoArticleIsSent() throws Exception {
        String body = mockMvc.perform(post("/api/dev/test-media")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID mediaId = UUID.fromString(objectMapper.readTree(body).get("mediaId").asText());
        UUID articleId = UUID.fromString(objectMapper.readTree(body).get("articleId").asText());

        assertThat(articleRepository.findById(articleId)).get()
                .extracting(article -> article.getDevMarker()).isEqualTo("e2e");
        assertThat(articleMediaRepository.findById(mediaId)).get()
                .extracting(media -> media.getArticleId()).isEqualTo(articleId);

        mockMvc.perform(delete("/api/dev/test-media/" + mediaId)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/dev/test-articles/" + articleId)).andExpect(status().isNoContent());
    }

    @Test
    void mintsAModerationTaskOnAMediaMetadataVersion() throws Exception {
        DevTestMediaResponse media = mintMedia(mediaRequest(mintArticleId(), ProcessingStatus.READY, PublicationStatus.PENDING));

        DevTestModerationResponse minted = mintModerationTask(new DevTestModerationRequest(null,
                ModerationTaskState.CLAIMED, null, null, null, media.firstMetadataVersionId()));
        assertThat(minted.revisionId()).isNull();
        assertThat(minted.mediaMetadataVersionId()).isEqualTo(media.firstMetadataVersionId());
        ModerationTask task = moderationTaskRepository.findById(minted.taskId()).orElseThrow();
        assertThat(task.getMediaMetadataVersionId()).isEqualTo(media.firstMetadataVersionId());
        assertThat(task.getClaimedBy()).isNotNull();

        // Both targets at once is refused by the exactly-one CHECK; an unknown version is a 404.
        mockMvc.perform(post("/api/dev/test-moderation-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DevTestModerationRequest(mintRevisionId(), null,
                                null, null, null, mintMedia(mediaRequest(mintArticleId(), null, null)).firstMetadataVersionId()))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/dev/test-moderation-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DevTestModerationRequest(null, null, null, null,
                                null, UUID.randomUUID()))))
                .andExpect(status().isNotFound());

        // Deleting the minted item takes its task with it.
        mockMvc.perform(delete("/api/dev/test-media/" + media.mediaId())).andExpect(status().isNoContent());
        assertThat(moderationTaskRepository.findById(minted.taskId())).isEmpty();
    }

    @Test
    void deletesOnlyMediaItMinted() throws Exception {
        UUID articleId = mintArticleId();
        DevTestMediaResponse minted = mintMedia(mediaRequest(articleId, null, null));
        ArticleMedia handMade = articleMediaRepository.save(ArticleMedia.builder()
                .articleId(articleId)
                .type(MediaKind.IMAGE)
                .uploadedBy(minted.uploadedByUserId())
                .declaredContentType("image/jpeg")
                .declaredBytes(10)
                .declaredSha256("wuaGgjSJztIBf2BZuLI5MYtjZPbc2DXQpRkQWh6t1uQ=")
                .processingStatus(ProcessingStatus.UPLOADING)
                .publicationStatus(PublicationStatus.PENDING)
                .variants(new ArrayList<>())
                .build());

        mockMvc.perform(delete("/api/dev/test-media/" + minted.mediaId())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/dev/test-media/" + minted.mediaId())).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/dev/test-media/" + handMade.getId())).andExpect(status().isNotFound());
        assertThat(articleMediaRepository.findById(minted.mediaId())).isEmpty();
        assertThat(mediaMetadataVersionRepository.findById(minted.firstMetadataVersionId())).isEmpty();
        assertThat(articleMediaRepository.findById(handMade.getId())).isPresent();

        mediaSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(articleMediaRepository.findById(handMade.getId())).isPresent();
    }

    @Test
    void sweeperReclaimsAgedMintedMediaAndArticleDeletionCascadesToIt() throws Exception {
        DevTestMediaResponse aged = mintMedia(mediaRequest(mintArticleId(), ProcessingStatus.READY, PublicationStatus.PUBLISHED));
        mediaSweeper.deleteOlderThan(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(articleMediaRepository.findById(aged.mediaId())).isPresent();
        mediaSweeper.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(articleMediaRepository.findById(aged.mediaId())).isEmpty();

        // Regression on the article fixture: deleting a minted article still works with gallery
        // rows hanging off it (article_media.article_id is ON DELETE CASCADE).
        UUID articleId = mintArticleId();
        DevTestMediaResponse onArticle = mintMedia(mediaRequest(articleId, null, null));
        mockMvc.perform(delete("/api/dev/test-articles/" + articleId)).andExpect(status().isNoContent());
        assertThat(articleMediaRepository.findById(onArticle.mediaId())).isEmpty();
    }
}
