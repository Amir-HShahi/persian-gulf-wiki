package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;
import java.util.List;

// Every subject link in an article body that names no subject, collected in one pass so the author
// sees them all at once. Each violation names its JSON path (e.g. "body.content[2].subjectId") and a
// message key, resolved in the reader's language by GlobalExceptionHandler.
public class InvalidArticleBodyException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "article body failed validation";

    // transient: an exception never crosses a serialization boundary here, and List promises no
    // Serializable implementation.
    private final transient List<Violation> violations;

    public InvalidArticleBodyException() {
        super(DEFAULT_MESSAGE);
        this.violations = List.of();
    }

    public InvalidArticleBodyException(String message) {
        super(message);
        this.violations = List.of();
    }

    public InvalidArticleBodyException(List<Violation> violations) {
        super(DEFAULT_MESSAGE + ": " + violations.stream().map(Violation::field).toList());
        this.violations = List.copyOf(violations);
    }

    public List<Violation> getViolations() {
        return violations;
    }

    // args are already strings, for the same reason as InvalidSubjectPayloadException.Violation's.
    public record Violation(String field, String messageKey, List<String> args) {

        public Violation {
            args = List.copyOf(args);
        }
    }
}
