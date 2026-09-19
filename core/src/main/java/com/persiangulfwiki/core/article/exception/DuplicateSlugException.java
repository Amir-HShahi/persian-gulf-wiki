package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class DuplicateSlugException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "slug already in use";

    public DuplicateSlugException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateSlugException(String message) {
        super(message);
    }
}
