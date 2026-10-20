package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

// A restore of a merged-away subject. A merge is not reversible: its facts, aliases and articles
// now belong to the target.
public class SubjectMergedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject was merged into another";

    public SubjectMergedException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectMergedException(String message) {
        super(message);
    }
}
