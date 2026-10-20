package com.persiangulfwiki.core.topic.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class TopicNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "topic not found";

    public TopicNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public TopicNotFoundException(String message) {
        super(message);
    }
}
