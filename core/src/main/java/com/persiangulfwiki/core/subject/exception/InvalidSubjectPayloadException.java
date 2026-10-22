package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;
import java.util.List;

// Every rule a subject revision's payload broke, collected in one pass so the author sees them all
// at once. Each violation names its field path (e.g. "payload.facts[2].values[0].sourceId") and a
// message key, resolved in the reader's language by GlobalExceptionHandler.
public class InvalidSubjectPayloadException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject payload failed validation";

    // transient: an exception never crosses a serialization boundary here, and List promises no
    // Serializable implementation.
    private final transient List<Violation> violations;

    public InvalidSubjectPayloadException() {
        super(DEFAULT_MESSAGE);
        this.violations = List.of();
    }

    public InvalidSubjectPayloadException(String message) {
        super(message);
        this.violations = List.of();
    }

    public InvalidSubjectPayloadException(List<Violation> violations) {
        super(DEFAULT_MESSAGE + ": " + violations.stream().map(Violation::field).toList());
        this.violations = List.copyOf(violations);
    }

    public List<Violation> getViolations() {
        return violations;
    }

    // args are already strings: MessageFormat would otherwise format a number in the reader's
    // locale, and a bound such as 500 must read the same as the limit the client was told.
    public record Violation(String field, String messageKey, List<String> args) {

        public Violation {
            args = List.copyOf(args);
        }
    }
}
