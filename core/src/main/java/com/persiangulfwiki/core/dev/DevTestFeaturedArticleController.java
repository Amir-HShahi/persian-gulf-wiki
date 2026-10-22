package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.entity.FeaturedArticle;
import com.persiangulfwiki.core.article.exception.ArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.FeaturedArticleNotFoundException;
import com.persiangulfwiki.core.article.exception.InvalidFeaturedArticleWindowException;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.FeaturedArticleRepository;
import com.persiangulfwiki.core.dev.dto.DevTestFeaturedArticleRequest;
import com.persiangulfwiki.core.dev.dto.DevTestFeaturedArticleResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// Mints throwaway featured-article windows, so an E2E suite can put any article (a minted one
// included) on the home page without an admin account. Same two gates and the same reasoning as
// DevTestSubjectController — see that class.
//
// Writes the row directly rather than through FeaturedArticleService, so a window that has
// already ended -- refused by nothing on the real path, but awkward to reach without waiting --
// is one call away. The minted window goes with its article through ON DELETE CASCADE (V34).
@RestController
@RequestMapping("/api/dev/test-featured-articles")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Featured Articles", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestFeaturedArticleController {

    private final FeaturedArticleRepository featuredArticleRepository;
    private final ArticleRepository articleRepository;

    @Operation(summary = "Mint a disposable featured-article window", description = "Features `articleId` from "
            + "`startsAt` (default now) until `endsAt` (default: no end). Both may be in the past, so an expired "
            + "window can be minted directly.")
    @ApiResponse(responseCode = "201", description = "The created window.")
    @ApiResponse(responseCode = "400", description = "`articleId` is missing, or `endsAt` is not after `startsAt`.")
    @ApiResponse(responseCode = "404", description = "No article with that `articleId`.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DevTestFeaturedArticleResponse mint(@Valid @RequestBody DevTestFeaturedArticleRequest request) {
        Instant startsAt = Objects.requireNonNullElseGet(request.startsAt(), Instant::now);
        // Checked here so a bad window is the same 400 as on the real path rather than a CHECK
        // violation.
        if (request.endsAt() != null && !request.endsAt().isAfter(startsAt)) {
            throw new InvalidFeaturedArticleWindowException();
        }
        if (!articleRepository.existsById(request.articleId())) {
            throw new ArticleNotFoundException();
        }
        FeaturedArticle window = featuredArticleRepository.save(FeaturedArticle.builder()
                .articleId(request.articleId())
                .startsAt(startsAt)
                .endsAt(request.endsAt())
                .devMarker(DevTestFixtures.MARKER)
                .build());
        return new DevTestFeaturedArticleResponse(window.getId());
    }

    @Operation(summary = "Delete a minted featured-article window", description = "Deletes a window minted by this "
            + "endpoint. Refuses anything else — a window an admin scheduled is reported as 404 rather than deleted.")
    @ApiResponse(responseCode = "204", description = "The window was deleted.")
    @ApiResponse(responseCode = "404", description = "No such window, or the id names one this endpoint did not mint.")
    @DeleteMapping("/{featuredArticleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID featuredArticleId) {
        // The marker check is what keeps this route off admin-made windows — see
        // DevTestSubjectController.delete.
        FeaturedArticle window = featuredArticleRepository.findById(featuredArticleId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(FeaturedArticleNotFoundException::new);
        featuredArticleRepository.delete(window);
    }
}
