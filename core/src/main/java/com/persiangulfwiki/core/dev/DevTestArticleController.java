package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.EntityType;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.entity.TranslationState;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.dev.dto.DevTestArticleRequest;
import com.persiangulfwiki.core.dev.dto.DevTestArticleResponse;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Mints a throwaway article + its canonical translation + that translation's first revision
// per call, for Phase 3/5 E2E suites that need a revision at a specific status without first
// driving an author + moderator through the real create/submit/review flow.
//
// Two independent gates keep this out of production, exactly as for DevTestSubjectController:
// @Profile("dev") here, so the bean does not exist, and DevSecurityConfig's profiled filter
// chain, so /api/dev/** is not permitted anywhere else. Both are needed.
//
// Deliberately bypasses ArticleService/ArticleRevisionService. The real path only ever
// inserts a revision as DRAFT (ArticleRevisionService.createInitialRevision hard-codes it),
// and the only way to move it onward is PATCH -> submit -> a moderator's decision in Phase 3 --
// so "an APPROVED revision with no moderation history at all" is unreachable through the API.
// That is exactly the state Phase 5's ExpertReview tests need as a fixture: proof that a
// display/aggregation path works against an approved revision without needing to also seed the
// ModerationTask that would normally have produced it. Writing all three rows directly through
// the repositories, with revisionStatus taken as-is from the request, is what makes it possible.
@RestController
@RequestMapping("/api/dev/test-articles")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Articles", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestArticleController {

    // Enough of the UUID to keep parallel runs from producing identical slugs/usernames in a
    // failing test's output, short enough to stay readable there.
    private static final int SLUG_LENGTH = 12;

    // A minimal but valid structured-content document, matching the shape Phase 4's renderer
    // expects to walk (see the JSON-mapping decision comment on ArticleRevision.body) --
    // exact structure doesn't matter for a fixture, only that it is valid JSON text, since the
    // column is jsonb.
    private static final String MINIMAL_BODY_JSON = "{\"type\":\"doc\",\"content\":[]}";

    private static final String DEFAULT_LANGUAGE = "fa";

    // DevUserSeeder's stable contributor fixture. Deliberately a seeded account rather than a
    // freshly minted one -- see resolveAuthorUserId for the sweep-ordering race that choice
    // avoids.
    private static final String SEEDED_AUTHOR_EMAIL = "contributor@dev.local";

    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;

    @Operation(summary = "Mint a disposable test article", description = "Creates an article, its canonical translation and that translation's first "
            + "revision, all marked as machine-minted. Every field of the request body is "
            + "optional. revisionStatus defaults to DRAFT but accepts any status, including "
            + "APPROVED with no prior moderation history -- a state the real submit/review "
            + "flow cannot produce.")
    @ApiResponse(responseCode = "201", description = "The created article, translation and revision.")
    @ApiResponse(responseCode = "404", description = "subjectId was supplied but does not name an existing subject.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Returns the DTO bare rather than wrapped in the usual envelope, matching
    // DevTestSubjectController: there is no message worth translating for a machine caller.
    public DevTestArticleResponse mint(@RequestBody(required = false) DevTestArticleRequest request) {
        DevTestArticleRequest safeRequest = request != null
                ? request
                : new DevTestArticleRequest(null, null, null, null, null, null, null, null, null);

        String slug = safeRequest.slug() != null ? safeRequest.slug() : DevTestFixtures.MARKER + "-article-" + randomSlug();
        String canonicalLanguage = safeRequest.canonicalLanguage() != null ? safeRequest.canonicalLanguage() : DEFAULT_LANGUAGE;
        RevisionStatus revisionStatus = safeRequest.revisionStatus() != null ? safeRequest.revisionStatus() : RevisionStatus.DRAFT;
        TranslationState translationState =
                safeRequest.translationState() != null ? safeRequest.translationState() : TranslationState.UP_TO_DATE;
        String title = safeRequest.title() != null ? safeRequest.title() : "e2e article " + slug;
        String summary = safeRequest.summary() != null ? safeRequest.summary() : "e2e summary";

        EntityType entityType = resolveEntityType(safeRequest.subjectId(), safeRequest.entityType());
        UUID authorUserId = resolveAuthorUserId(safeRequest.authorUserId());

        Article article = articleRepository.save(Article.builder()
                .subjectId(safeRequest.subjectId())
                .entityType(entityType)
                .canonicalLanguage(canonicalLanguage)
                .createdByUserId(authorUserId)
                .devMarker(DevTestFixtures.MARKER)
                .build());

        ArticleTranslation translation = articleTranslationRepository.save(ArticleTranslation.builder()
                .articleId(article.getId())
                .language(canonicalLanguage)
                .slug(slug)
                .translationState(translationState)
                .build());

        ArticleRevision revision = articleRevisionRepository.save(ArticleRevision.builder()
                .translationId(translation.getId())
                .revisionNumber(1)
                .parentRevisionId(null)
                .title(title)
                .body(MINIMAL_BODY_JSON)
                .summary(summary)
                .status(revisionStatus)
                .authorId(authorUserId)
                .build());

        // Mirrors the production invariant rather than the old unconditional assignment: a
        // translation's currentRevisionId names approved content only (see
        // ArticleTranslation.currentRevisionId). A fixture minted at DRAFT/PENDING/REJECTED
        // must therefore look unpublished, or a suite asserting "approval is what publishes"
        // would pass against a fixture that was never approved.
        //
        // Set directly rather than through the moderation flow, which is the point of this
        // endpoint: an APPROVED revision that is genuinely published, with no ModerationTask
        // or decision history behind it, is not reachable any other way.
        if (revisionStatus == RevisionStatus.APPROVED) {
            translation.setCurrentRevisionId(revision.getId());
            articleTranslationRepository.save(translation);
        }

        return new DevTestArticleResponse(
                article.getId(), translation.getId(), revision.getId(), canonicalLanguage, slug, revisionStatus);
    }

    @Operation(summary = "Delete a minted test article", description = "Deletes an article previously created by this endpoint. Refuses anything "
            + "else -- an article created through the normal endpoint is reported as 404 "
            + "rather than deleted. Idempotent: deleting an id twice returns 404 the second "
            + "time. The canonical translation and its revisions are removed along with it.")
    @ApiResponse(responseCode = "204", description = "The article was deleted.")
    @ApiResponse(responseCode = "404", description = "No such article, or the id names one this endpoint did not mint.")
    @DeleteMapping("/{articleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID articleId) {
        // Deliberately narrower than "delete whatever id I am given" -- see
        // DevTestSubjectController.delete for why the marker check is load-bearing.
        Article article = articleRepository.findById(articleId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                // Reuses the production exception rather than a dev-only one:
                // GlobalExceptionHandler already maps it to 404 ARTICLE_NOT_FOUND.
                .orElseThrow(ArticleNotFoundException::new);

        // article_translations.article_id and article_revisions.translation_id are both
        // ON DELETE CASCADE on their parents (see V16) -- so the canonical translation and
        // its revision(s) go with it without an explicit cleanup pass here.
        articleRepository.delete(article);
    }

    // Derivation mirrors ArticleService.resolveEntityType for the subjectId != null case
    // (read the bound Subject's kind), but does not reproduce its cross-field rejection: a
    // caller-supplied entityType alongside subjectId is silently ignored rather than throwing
    // EntityTypeNotDerivableException, since this endpoint already bypasses the service
    // layer's other invariants and a fixture caller has no reason to send both. When
    // subjectId is null, unlike the production path (which only ever accepts GENERIC), any
    // requested value is honored -- including STRAIT, which EntityType's own comment says is
    // "selectable directly for a subject-less article" but which the real create endpoint
    // cannot reach until a STRAIT SubjectKind exists.
    private EntityType resolveEntityType(UUID subjectId, EntityType requestedEntityType) {
        if (subjectId != null) {
            Subject subject = subjectRepository.findById(subjectId).orElseThrow(SubjectNotFoundException::new);
            return EntityType.valueOf(subject.getKind().name());
        }
        return requestedEntityType != null ? requestedEntityType : EntityType.GENERIC;
    }

    // article_revisions.author_id is NOT NULL with no ON DELETE action (V16) -- inserting an
    // arbitrary or fabricated UUID here would fail the foreign key at insert time, not
    // silently produce a dangling reference. Rejecting an omitted authorUserId outright would
    // need a new exception class, a GlobalExceptionHandler entry and a translated message
    // (per exception-handling.md / translating.md) for a caller that, in the common case,
    // doesn't care who authored the fixture at all.
    //
    // So an omitted author falls back to DevUserSeeder's contributor@dev.local, and that
    // choice is load-bearing rather than arbitrary: a seeded account carries no e2e- prefix,
    // so DevTestUserSweeper can never reclaim it (see DevTestUsers for why the prefix, not
    // the @dev.local domain, is what separates the two populations). Minting a fresh
    // disposable user here instead would be the obvious move and is the wrong one -- that
    // user and this article are created in the same instant with the same TTL, but
    // DevTestArticleSweeper runs at :20 and DevTestUserSweeper at :30, so the user sweep's
    // threshold is ten minutes more permissive than the article sweep's. A pair created
    // inside that ten-minute band has its user swept while its article survives, and
    // author_id's RESTRICT then aborts the *entire* user sweep pass, not just the blocked
    // row. Pointing at a never-swept account removes that race rather than timing around it.
    //
    // The disposable-mint path below survives only for the case where the seeded account is
    // genuinely absent (someone deleted it by hand), so this endpoint still works rather than
    // failing on a missing fixture.
    private UUID resolveAuthorUserId(UUID requestedAuthorUserId) {
        if (requestedAuthorUserId != null) {
            return requestedAuthorUserId;
        }
        return userRepository.findByEmail(SEEDED_AUTHOR_EMAIL)
                .map(User::getId)
                .orElseGet(this::mintDisposableAuthor);
    }

    private UUID mintDisposableAuthor() {
        String slug = randomSlug();
        User author = userRepository.save(User.builder()
                .username(DevTestUsers.USERNAME_PREFIX + slug)
                .email(DevTestUsers.EMAIL_PREFIX + slug + DevTestUsers.EMAIL_DOMAIN)
                .enabled(true)
                .emailVerified(true)
                .build());
        return author.getId();
    }

    private String randomSlug() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
    }
}
