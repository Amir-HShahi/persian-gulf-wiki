package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

// endsAt is not after startsAt. Reported against the request's `endsAt` field by
// GlobalExceptionHandler, like InvalidMainArticleException on `articleId`.
public class InvalidFeaturedArticleWindowException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "featured article window ends before it starts";

    public InvalidFeaturedArticleWindowException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidFeaturedArticleWindowException(String message) {
        super(message);
    }
}
