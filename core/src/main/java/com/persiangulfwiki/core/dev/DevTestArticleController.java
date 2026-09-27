package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.dev.dto.DevTestArticleRequest;
import com.persiangulfwiki.core.dev.dto.DevTestArticleResponse;

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

    private final ArticleRepository articleRepository;
    private final DevTestArticleFixtures articleFixtures;

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
        return articleFixtures.mint(request != null
                ? request
                : new DevTestArticleRequest(null, null, null, null, null, null, null, null, null));
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
}
