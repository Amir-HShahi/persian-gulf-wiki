package com.persiangulfwiki.core.expertreviewer.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class ApplicationAlreadyReviewedException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "application has already been reviewed";

    public ApplicationAlreadyReviewedException() {
        super(DEFAULT_MESSAGE);
    }

    public ApplicationAlreadyReviewedException(String message) {
        super(message);
    }
}
