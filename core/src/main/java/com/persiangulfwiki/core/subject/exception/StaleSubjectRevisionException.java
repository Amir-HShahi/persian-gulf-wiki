package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import org.jspecify.annotations.Nullable;

import java.io.Serial;

// An edit copied from a revision that is no longer live. Submitting or approving it would
// overwrite whatever was approved in between, so the author has to start a new revision from the
// current one. Carries both revision numbers for the client-facing message ("based on r3, live is
// r4"); baseRevisionNumber is null when the edit was started before the subject had any approved
// revision, and liveRevisionNumber when the subject has none now.
public class StaleSubjectRevisionException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject revision is not based on the live revision";

    private final @Nullable Integer baseRevisionNumber;
    private final @Nullable Integer liveRevisionNumber;

    public StaleSubjectRevisionException() {
        super(DEFAULT_MESSAGE);
        this.baseRevisionNumber = null;
        this.liveRevisionNumber = null;
    }

    public StaleSubjectRevisionException(String message) {
        super(message);
        this.baseRevisionNumber = null;
        this.liveRevisionNumber = null;
    }

    public StaleSubjectRevisionException(@Nullable Integer baseRevisionNumber, @Nullable Integer liveRevisionNumber) {
        super(DEFAULT_MESSAGE + ": based on " + baseRevisionNumber + ", live is " + liveRevisionNumber);
        this.baseRevisionNumber = baseRevisionNumber;
        this.liveRevisionNumber = liveRevisionNumber;
    }

    public @Nullable Integer getBaseRevisionNumber() {
        return baseRevisionNumber;
    }

    public @Nullable Integer getLiveRevisionNumber() {
        return liveRevisionNumber;
    }
}
