package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

// Not in the brief's explicit exception list, added to satisfy its own instruction that the
// GET /api/articles `entityType` filter be "parsed in the service into a translated 400,
// exactly as SubjectService.parseKind does" -- that pattern requires a dedicated exception
// distinct from EntityTypeNotDerivableException, which covers a different scenario (the
// create-time subjectId/entityType invariant, not an unparseable filter string).
public class InvalidEntityTypeException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "invalid entity type filter";

    public InvalidEntityTypeException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidEntityTypeException(String message) {
        super(message);
    }
}
