package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class FeaturedArticleNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "featured article not found";

    public FeaturedArticleNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public FeaturedArticleNotFoundException(String message) {
        super(message);
    }
}
