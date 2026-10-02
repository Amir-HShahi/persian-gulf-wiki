package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class RevisionNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "revision not found";

    public RevisionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public RevisionNotFoundException(String message) {
        super(message);
    }
}
