package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectPayloadException.Violation;

import java.io.Serial;
import java.util.List;

// The payload a merge would publish on the target fails the field vocabulary -- a field became
// required after both subjects were approved, or the two together exceed a cap such as the alias
// limit. A 409, not a 400: the request (just a target id) is fine, the two subjects' current state
// is what conflicts. Carries the violations, each a path into the merged payload, so the moderator
// knows what to fix with a normal edit of the target or source before merging again.
public class SubjectMergeInvalidException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "merged subject payload failed validation";

    // transient for the reason InvalidSubjectPayloadException's list is.
    private final transient List<Violation> violations;

    public SubjectMergeInvalidException() {
        super(DEFAULT_MESSAGE);
        this.violations = List.of();
    }

    public SubjectMergeInvalidException(String message) {
        super(message);
        this.violations = List.of();
    }

    public SubjectMergeInvalidException(List<Violation> violations) {
        super(DEFAULT_MESSAGE + ": " + violations.stream().map(Violation::field).toList());
        this.violations = List.copyOf(violations);
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
