package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class ArticleNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "article not found";

    public ArticleNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public ArticleNotFoundException(String message) {
        super(message);
    }
}
