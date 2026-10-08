package com.persiangulfwiki.core.topic.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

// A request's `topicIds` can't be accepted: an id names no topic, names an archived topic the
// revision didn't already have, or the revision is not in its article's canonical language.
// Reported against `topicIds` with the message key, resolved in the reader's language by
// GlobalExceptionHandler.
public class InvalidTopicIdsException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid topic ids";
    private static final String DEFAULT_MESSAGE_KEY = "validation.topicIds.notFound";

    private final String messageKey;

    public InvalidTopicIdsException() {
        super(DEFAULT_MESSAGE);
        this.messageKey = DEFAULT_MESSAGE_KEY;
    }

    public InvalidTopicIdsException(String message) {
        super(message);
        this.messageKey = DEFAULT_MESSAGE_KEY;
    }

    public InvalidTopicIdsException(String message, String messageKey) {
        super(message);
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
