package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class DuplicateTranslationLanguageException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "translation already exists for this language";

    public DuplicateTranslationLanguageException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateTranslationLanguageException(String message) {
        super(message);
    }
}
