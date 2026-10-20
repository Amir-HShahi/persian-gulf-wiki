package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class SubjectRevisionNotLiveException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject revision is not its subject's live revision";

    public SubjectRevisionNotLiveException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectRevisionNotLiveException(String message) {
        super(message);
    }
}
