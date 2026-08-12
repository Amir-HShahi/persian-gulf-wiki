package com.persiangulfwiki.core.auth.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class DuplicateUserException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "username or email already in use";
    private static final String DEFAULT_MESSAGE_KEY = "error.duplicateUser.generic";

    // The technical message (super's) is for logs/stack traces only — GlobalExceptionHandler
    // never returns it to the client, resolving messageKey through MessageSource instead so
    // the client-facing text is localized.
    private final String messageKey;

    public DuplicateUserException() {
        super(DEFAULT_MESSAGE);
        this.messageKey = DEFAULT_MESSAGE_KEY;
    }

    public DuplicateUserException(String message) {
        super(message);
        this.messageKey = DEFAULT_MESSAGE_KEY;
    }

    private DuplicateUserException(String message, String messageKey) {
        super(message);
        this.messageKey = messageKey;
    }

    public static DuplicateUserException emailInUse() {
        return new DuplicateUserException("email already in use", "error.duplicateUser.email");
    }

    public static DuplicateUserException usernameInUse() {
        return new DuplicateUserException("username already in use", "error.duplicateUser.username");
    }

    public String getMessageKey() {
        return messageKey;
    }
}
