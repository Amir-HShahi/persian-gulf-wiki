package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class DevTestSubjectRevisionLiveException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "minted subject revision is its subject's live revision";

    public DevTestSubjectRevisionLiveException() {
        super(DEFAULT_MESSAGE);
    }

    public DevTestSubjectRevisionLiveException(String message) {
        super(message);
    }
}
