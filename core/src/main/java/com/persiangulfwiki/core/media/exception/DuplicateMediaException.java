package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class DuplicateMediaException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "this file is already in the article's gallery";

    public DuplicateMediaException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateMediaException(String message) {
        super(message);
    }
}
