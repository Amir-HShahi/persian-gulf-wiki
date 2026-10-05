package com.persiangulfwiki.core.article.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdateMainArticleRequest(
        @NotNull(message = "{validation.mainArticle.required}") UUID articleId) {
}
