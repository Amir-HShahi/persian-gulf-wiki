package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;
import java.util.List;

// Every problem with the subject listing's filter parameters, collected in one pass. Each violation
// names the query parameter (e.g. "attr.iucn_status") and a message key, resolved in the reader's
// language by GlobalExceptionHandler.
public class InvalidSubjectFilterException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject listing filter failed validation";

    // transient: an exception never crosses a serialization boundary here, and List promises no
    // Serializable implementation.
    private final transient List<Violation> violations;

    public InvalidSubjectFilterException() {
        super(DEFAULT_MESSAGE);
        this.violations = List.of();
    }

    public InvalidSubjectFilterException(String message) {
        super(message);
        this.violations = List.of();
    }

    public InvalidSubjectFilterException(List<Violation> violations) {
        super(DEFAULT_MESSAGE + ": " + violations.stream().map(Violation::parameter).toList());
        this.violations = List.copyOf(violations);
    }

    public List<Violation> getViolations() {
        return violations;
    }

    public record Violation(String parameter, String messageKey, List<String> args) {

        public Violation {
            args = List.copyOf(args);
        }
    }
}
