package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class DuplicateTranslationLanguageException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "translation already exists for this language";

    public DuplicateTranslationLanguageException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateTranslationLanguageException(String message) {
        super(message);
    }
}
