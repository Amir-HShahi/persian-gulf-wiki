package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class RevisionNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "revision not found";

    public RevisionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public RevisionNotFoundException(String message) {
        super(message);
    }
}
