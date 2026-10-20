package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class DuplicateSlugException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "slug already in use";

    public DuplicateSlugException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateSlugException(String message) {
        super(message);
    }
}
