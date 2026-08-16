---
name: exception-handling
description: Creates/modifies exception classes and their GlobalExceptionHandler wiring in `core` — class shape, base-class-to-HTTP-status mapping, ProblemDetail handler, and the mandatory translated client message. Trigger before creating or modifying any exception class or exception-handling logic (@ControllerAdvice, @ExceptionHandler), per code-style.md's Exception handling rule. Invokes `translating` internally for the client-facing message key — don't invoke `translating` separately for this case.
---

## 1. Exception class shape (mandatory, no variation)

```java
package com.persiangulfwiki.core.<feature>.exception;

import com.persiangulfwiki.core.common.exception.<CorrespondingBaseException>;

public class <Name>Exception extends <CorrespondingBaseException> {

    private static final String DEFAULT_MESSAGE = "<lowercase technical message>";

    public <Name>Exception() {
        super(DEFAULT_MESSAGE);
    }

    public <Name>Exception(String message) {
        super(message);
    }
}
```

- Always both constructors — no-arg using `DEFAULT_MESSAGE`, and a `String message` overload. Never add other constructors (no cause-chaining constructor unless a real call site needs one).
- `DEFAULT_MESSAGE` is a technical, lowercase, English string for logs only — it is never what the client sees (see step 3).
- Package is `com.persiangulfwiki.core.<feature>.exception`, matching the throwing feature's package (package-by-feature).
- Class name ends in `Exception`.
- Base class is picked by the HTTP status the situation maps to, per api-conventions.md's status table — not decided ad hoc:
    - 404 → `NotFoundException`
    - 409 → `ConflictException`
    - 400 → `BadRequestException`
    - 401/403 → check GlobalExceptionHandler (@core/src/main/java/com/persiangulfwiki/core/web/GlobalExceptionHandler.java) for an existing base first (e.g. `InvalidCredentialsException`, `AccessDeniedException` handling); if none fits, ask rather than inventing a new base silently — base classes are shared infrastructure, a bigger decision than a leaf exception.
- All exceptions are unchecked (the three base classes extend `RuntimeException`) — never a checked exception.

## 2. Wiring into GlobalExceptionHandler

- The single `@RestControllerAdvice` is @core/src/main/java/com/persiangulfwiki/core/web/GlobalExceptionHandler.java. Every exception gets exactly one `@ExceptionHandler` method there — never let a service or repository construct a `ProblemDetail`/status code itself.
- Add:

```java
@ExceptionHandler(<Name>Exception.class)
public ProblemDetail handle<Name>(<Name>Exception ex, HttpServletRequest request) {
    return ProblemDetails.of(HttpStatus.<STATUS>, resolve("error.<camelCaseKey>"), "<UPPER_SNAKE_CODE>", request);
}
```

- `HttpStatus.<STATUS>` must match the status implied by the exception's base class (`NotFoundException`→404, `ConflictException`→409, `BadRequestException`→400). Never pick a different status than the base class's contract — change the base class choice instead.
- `resolve("error.<camelCaseKey>")` — not `ex.getMessage()` — is what the client receives; `ex.getMessage()`/`DEFAULT_MESSAGE` stays log-only.
- `"<UPPER_SNAKE_CODE>"` is the exception's name with the `Exception` suffix stripped, converted to UPPER_SNAKE_CASE (matches the `codeFor()` fallback convention, e.g. `SessionAlreadyRevokedException` → `SESSION_ALREADY_REVOKED`).

## 3. Translating the client-facing message (mandatory sub-step)

- Every new `error.<camelCaseKey>` used in a `resolve(...)` call must be added via the `translating` skill, across all three locale bundles (messages.properties, messages_fa.properties, messages_ar.properties — Farsi is the reference locale). Never hand-edit the properties files directly.
- This is required, not optional polish — the handler method isn't done until `translating` has been invoked for its key.

## 4. Logging (log once, at the boundary)

- Don't log inside the exception class, the throwing service, or any intermediate layer.
- If an occurrence needs an application log entry (beyond the HTTP response), log it exactly once, inside the GlobalExceptionHandler method, with `@Slf4j` and parameterized logging (`log.warn("...", ...)`).
- Most 4xx cases (expected client errors, e.g. not-found/conflict) don't need a log line at all — reserve logging for genuinely unexpected/investigatable cases.

## 5. Worked example

`SessionAlreadyRevokedException` (@core/src/main/java/com/persiangulfwiki/core/user/exception/SessionAlreadyRevokedException.java) is the canonical reference:

```java
package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class SessionAlreadyRevokedException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "session already revoked";

    public SessionAlreadyRevokedException() {
        super(DEFAULT_MESSAGE);
    }

    public SessionAlreadyRevokedException(String message) {
        super(message);
    }
}
```

paired with its handler in GlobalExceptionHandler:

```java
@ExceptionHandler(SessionAlreadyRevokedException.class)
public ProblemDetail handleSessionAlreadyRevoked(SessionAlreadyRevokedException ex, HttpServletRequest request) {
    return ProblemDetails.of(HttpStatus.CONFLICT, resolve("error.sessionAlreadyRevoked"), "SESSION_ALREADY_REVOKED", request);
}
```

and its `error.sessionAlreadyRevoked` key in the three locale bundles. Model any new exception on this end-to-end shape: class → handler → translated key.
