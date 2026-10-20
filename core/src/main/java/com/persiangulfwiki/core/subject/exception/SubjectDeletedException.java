package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

// A write aimed at a subject that has been soft-deleted or merged away: editing it, deciding or
// verifying one of its revisions, or deleting or merging it again. Reads of a deleted subject are
// 404s instead (SubjectNotFoundException), since to readers it no longer exists.
public class SubjectDeletedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject is deleted";

    public SubjectDeletedException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectDeletedException(String message) {
        super(message);
    }
}
