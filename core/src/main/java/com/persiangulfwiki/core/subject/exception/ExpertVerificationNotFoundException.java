package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class ExpertVerificationNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "expert verification not found";

    public ExpertVerificationNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public ExpertVerificationNotFoundException(String message) {
        super(message);
    }
}
