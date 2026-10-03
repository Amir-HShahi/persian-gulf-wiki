package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ForbiddenException;

import java.io.Serial;

public class SelfVerificationNotAllowedException extends ForbiddenException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "an expert cannot verify a subject revision they authored";

    public SelfVerificationNotAllowedException() {
        super(DEFAULT_MESSAGE);
    }

    public SelfVerificationNotAllowedException(String message) {
        super(message);
    }
}
