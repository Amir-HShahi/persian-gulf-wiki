package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

// A delete of a subject that articles are about. Articles never change subject, so such a subject can
// only be merged into another.
public class SubjectHasArticlesException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "articles are bound to the subject";

    public SubjectHasArticlesException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectHasArticlesException(String message) {
        super(message);
    }
}
