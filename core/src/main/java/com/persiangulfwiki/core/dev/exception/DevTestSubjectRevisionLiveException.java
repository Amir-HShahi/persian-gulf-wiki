package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class DevTestSubjectRevisionLiveException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "minted subject revision is its subject's live revision";

    public DevTestSubjectRevisionLiveException() {
        super(DEFAULT_MESSAGE);
    }

    public DevTestSubjectRevisionLiveException(String message) {
        super(message);
    }
}
