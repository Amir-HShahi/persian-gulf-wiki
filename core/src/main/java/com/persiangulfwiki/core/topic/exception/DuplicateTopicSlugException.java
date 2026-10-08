package com.persiangulfwiki.core.topic.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class DuplicateTopicSlugException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "topic slug already exists";

    public DuplicateTopicSlugException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateTopicSlugException(String message) {
        super(message);
    }
}
