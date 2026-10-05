package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

// A fixture asked to be its subject's main article while the subject already has one. The fixture
// refuses rather than demoting it, since that article may not be one the fixture minted.
public class DevTestMainArticleTakenException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject already has a main article";

    public DevTestMainArticleTakenException() {
        super(DEFAULT_MESSAGE);
    }

    public DevTestMainArticleTakenException(String message) {
        super(message);
    }
}
