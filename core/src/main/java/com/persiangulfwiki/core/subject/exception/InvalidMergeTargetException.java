package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

// The subject a moderator named to merge into cannot take the merge: it does not exist, is deleted,
// or is the source itself. Reported against the request's `targetId` field with the message key,
// like InvalidMainArticleException.
public class InvalidMergeTargetException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject cannot be the merge target";
    private static final String DEFAULT_MESSAGE_KEY = "validation.subjectMerge.targetNotFound";

    private final String messageKey;

    public InvalidMergeTargetException() {
        super(DEFAULT_MESSAGE);
        this.messageKey = DEFAULT_MESSAGE_KEY;
    }

    public InvalidMergeTargetException(String message) {
        super(message);
        this.messageKey = DEFAULT_MESSAGE_KEY;
    }

    public InvalidMergeTargetException(String message, String messageKey) {
        super(message);
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
