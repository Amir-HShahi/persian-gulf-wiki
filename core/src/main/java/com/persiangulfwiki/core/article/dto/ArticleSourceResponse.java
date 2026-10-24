package com.persiangulfwiki.core.article.dto;

import java.util.UUID;

public record ArticleSourceResponse(UUID translationId, String language, String slug) {
}
