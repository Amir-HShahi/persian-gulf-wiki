package com.persiangulfwiki.core.user.dto;

import com.persiangulfwiki.core.article.dto.ArticleCardResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record ContributedArticleResponse(
        ArticleCardResponse article,
        @Schema(description = "What the user did on this article.\n\n" + ContributionType.VALUES_DOC)
        List<ContributionType> contributionTypes) {
}
