package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class ArticleNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "article not found";

    public ArticleNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public ArticleNotFoundException(String message) {
        super(message);
    }
}
