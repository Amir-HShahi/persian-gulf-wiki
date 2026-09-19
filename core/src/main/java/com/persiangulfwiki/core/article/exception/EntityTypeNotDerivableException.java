package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class EntityTypeNotDerivableException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "entity type could not be derived from the request";

    public EntityTypeNotDerivableException() {
        super(DEFAULT_MESSAGE);
    }

    public EntityTypeNotDerivableException(String message) {
        super(message);
    }
}
