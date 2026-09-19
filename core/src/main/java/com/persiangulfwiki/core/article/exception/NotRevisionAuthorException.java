package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.ForbiddenException;

public class NotRevisionAuthorException extends ForbiddenException {

    private static final String DEFAULT_MESSAGE = "caller is not the author of this revision";

    public NotRevisionAuthorException() {
        super(DEFAULT_MESSAGE);
    }

    public NotRevisionAuthorException(String message) {
        super(message);
    }
}
