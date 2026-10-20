package com.persiangulfwiki.core.expertreviewer.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class ApplicationAlreadyReviewedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "application has already been reviewed";

    public ApplicationAlreadyReviewedException() {
        super(DEFAULT_MESSAGE);
    }

    public ApplicationAlreadyReviewedException(String message) {
        super(message);
    }
}
