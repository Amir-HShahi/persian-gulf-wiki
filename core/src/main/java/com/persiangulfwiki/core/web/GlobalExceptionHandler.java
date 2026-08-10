package com.persiangulfwiki.core.web;

import com.persiangulfwiki.core.admin.exception.InvalidPagingParametersException;
import com.persiangulfwiki.core.admin.exception.LastAdminException;
import com.persiangulfwiki.core.admin.exception.RoleAlreadyGrantedException;
import com.persiangulfwiki.core.admin.exception.RoleNotGrantedException;
import com.persiangulfwiki.core.auth.exception.AccountDisabledException;
import com.persiangulfwiki.core.auth.exception.DuplicateUserException;
import com.persiangulfwiki.core.auth.exception.InvalidCredentialsException;
import com.persiangulfwiki.core.auth.exception.InvalidRefreshTokenException;
import com.persiangulfwiki.core.emailverification.exception.InvalidEmailVerificationTokenException;
import com.persiangulfwiki.core.expertreviewer.exception.ApplicationAlreadyReviewedException;
import com.persiangulfwiki.core.expertreviewer.exception.ApplicationNotFoundException;
import com.persiangulfwiki.core.expertreviewer.exception.DuplicatePendingApplicationException;
import com.persiangulfwiki.core.expertreviewer.exception.InvalidApplicationStatusException;
import com.persiangulfwiki.core.oauth2.exception.PasswordAlreadySetException;
import com.persiangulfwiki.core.password.exception.InvalidPasswordResetTokenException;
import com.persiangulfwiki.core.user.exception.SessionAlreadyRevokedException;
import com.persiangulfwiki.core.user.exception.SessionNotFoundException;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.slf4j.MDC;

// Extends ResponseEntityExceptionHandler so framework-thrown request exceptions
// (validation failures, malformed JSON, unsupported methods, etc.) keep Spring's own
// ProblemDetail handling untouched — this class only adds handlers for exceptions that
// Spring doesn't already know how to map, plus a last-resort 500 fallback. Method
// resolution picks the most specific declared exception type, so the Exception.class
// fallback below never preempts the inherited, more specific handlers.
//
// Every handler builds its body through ProblemDetails.of(status, detail, code, request) so
// the response shape never drifts between handlers: type/title/status/detail/instance plus
// the extension properties code/timestamp/traceId are always present together. `code` is a
// stable machine-readable discriminator per exception type, so the frontend can branch on it
// instead of string-matching `detail`.
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final MessageSource messageSource;

    // Every client-facing "detail" string in this class goes through here rather than
    // ex.getMessage() — exception messages are technical/English (for logs), this resolves
    // the request's locale (fa by default, see LocaleConfig) against messages*.properties.
    private String resolve(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.UNAUTHORIZED, resolve("error.invalidCredentials"), "INVALID_CREDENTIALS", request);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.UNAUTHORIZED, resolve("error.invalidRefreshToken"), "INVALID_REFRESH_TOKEN", request);
    }

    @ExceptionHandler(AccountDisabledException.class)
    public ProblemDetail handleAccountDisabled(AccountDisabledException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.UNAUTHORIZED, resolve("error.accountDisabled"), "ACCOUNT_DISABLED", request);
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ProblemDetail handleInvalidPasswordResetToken(InvalidPasswordResetTokenException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.UNAUTHORIZED, resolve("error.invalidPasswordResetToken"), "INVALID_PASSWORD_RESET_TOKEN", request);
    }

    @ExceptionHandler(InvalidEmailVerificationTokenException.class)
    public ProblemDetail handleInvalidEmailVerificationToken(InvalidEmailVerificationTokenException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.UNAUTHORIZED, resolve("error.invalidEmailVerificationToken"), "INVALID_EMAIL_VERIFICATION_TOKEN", request);
    }

    @ExceptionHandler(DuplicateUserException.class)
    public ProblemDetail handleDuplicateUser(DuplicateUserException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, resolve(ex.getMessageKey()), "DUPLICATE_USER", request);
    }

    @ExceptionHandler(PasswordAlreadySetException.class)
    public ProblemDetail handlePasswordAlreadySet(PasswordAlreadySetException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, resolve("error.passwordAlreadySet"), "PASSWORD_ALREADY_SET", request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, resolve("error.userNotFound"), "USER_NOT_FOUND", request);
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(SessionNotFoundException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, resolve("error.sessionNotFound"), "SESSION_NOT_FOUND", request);
    }

    @ExceptionHandler(SessionAlreadyRevokedException.class)
    public ProblemDetail handleSessionAlreadyRevoked(SessionAlreadyRevokedException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, resolve("error.sessionAlreadyRevoked"), "SESSION_ALREADY_REVOKED", request);
    }

    @ExceptionHandler(LastAdminException.class)
    public ProblemDetail handleLastAdmin(LastAdminException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, resolve("error.lastAdmin"), "LAST_ADMIN", request);
    }

    @ExceptionHandler(RoleAlreadyGrantedException.class)
    public ProblemDetail handleRoleAlreadyGranted(RoleAlreadyGrantedException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, resolve("error.roleAlreadyGranted"), "ROLE_ALREADY_GRANTED", request);
    }

    @ExceptionHandler(RoleNotGrantedException.class)
    public ProblemDetail handleRoleNotGranted(RoleNotGrantedException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, resolve("error.roleNotGranted"), "ROLE_NOT_GRANTED", request);
    }

    @ExceptionHandler(ApplicationNotFoundException.class)
    public ProblemDetail handleApplicationNotFound(ApplicationNotFoundException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, resolve("error.applicationNotFound"), "APPLICATION_NOT_FOUND", request);
    }

    @ExceptionHandler(DuplicatePendingApplicationException.class)
    public ProblemDetail handleDuplicatePendingApplication(DuplicatePendingApplicationException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, resolve("error.duplicatePendingApplication"), "DUPLICATE_PENDING_APPLICATION", request);
    }

    @ExceptionHandler(ApplicationAlreadyReviewedException.class)
    public ProblemDetail handleApplicationAlreadyReviewed(ApplicationAlreadyReviewedException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, resolve("error.applicationAlreadyReviewed"), "APPLICATION_ALREADY_REVIEWED", request);
    }

    @ExceptionHandler(InvalidPagingParametersException.class)
    public ProblemDetail handleInvalidPagingParameters(InvalidPagingParametersException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, resolve("error.invalidPagingParameters"), "INVALID_PAGING_PARAMETERS", request);
    }

    @ExceptionHandler(InvalidApplicationStatusException.class)
    public ProblemDetail handleInvalidApplicationStatus(InvalidApplicationStatusException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, resolve("error.invalidApplicationStatus"), "INVALID_APPLICATION_STATUS", request);
    }

    // ResponseEntityExceptionHandler's own ~20 built-in handlers (malformed JSON body,
    // unsupported HTTP method, unmatched route → 404, missing/invalid request param, etc.)
    // all funnel through this single method before returning. Without this override, those
    // responses would still be a ProblemDetail (spring.mvc.problemdetails.enabled=true) but
    // would skip code/timestamp/traceId/instance entirely, breaking the "every error response
    // has the same fields" guarantee for anything we didn't write a handler for ourselves.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problemDetail) {
            HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
            if (problemDetail.getInstance() == null) {
                problemDetail.setInstance(URI.create(servletRequest.getRequestURI()));
            }
            if (problemDetail.getProperties() == null || !problemDetail.getProperties().containsKey("code")) {
                problemDetail.setProperty("code", codeFor(ex));
            }
            problemDetail.setProperty("timestamp", Instant.now());
            problemDetail.setProperty("traceId", MDC.get(ProblemDetails.TRACE_ID_MDC_KEY));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // Generic fallback so every framework exception gets a stable, machine-readable code
    // without having to enumerate each of ResponseEntityExceptionHandler's ~20 built-ins by
    // hand: HttpMessageNotReadableException -> HTTP_MESSAGE_NOT_READABLE, etc.
    private static String codeFor(Exception ex) {
        String simpleName = ex.getClass().getSimpleName().replaceFirst("Exception$", "");
        return simpleName.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }

    // Overridden so @Valid failures (e.g. @ValidPassword) surface per-field reasons instead
    // of Spring's generic "Invalid request content." "detail" is just a flat human-readable
    // headline; "errors" is the machine-readable per-field list the frontend should actually
    // key off of to place messages under each field without reparsing text. "errors" only
    // appears here — every other handler's client should rely on "code" instead.
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();

        List<FieldViolation> errors = fieldErrors.stream()
                .map(fieldError -> new FieldViolation(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        ProblemDetail problemDetail = ProblemDetails.of(
                HttpStatus.BAD_REQUEST, resolve("error.validationFailed"), "VALIDATION_FAILED", servletRequest);
        problemDetail.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problemDetail);
    }

    private record FieldViolation(String field, String message) {
    }

    // Without this, a @PreAuthorize denial (thrown as AuthorizationDeniedException, a
    // subtype of this) would fall through to the Exception.class fallback below and return
    // 500 instead of 403 — the catch-all @RestControllerAdvice here handles it via Spring
    // MVC's exception resolution before Spring Security's own ExceptionTranslationFilter
    // ever gets a chance to.
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.FORBIDDEN, resolve("error.accessDenied"), "ACCESS_DENIED", request);
    }

    // Safety net for check-then-insert races (e.g. the register uniqueness check) —
    // never leak the raw constraint-violation message to the client.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        return ProblemDetails.of(
                HttpStatus.CONFLICT, resolve("error.duplicateUser.generic"), "DATA_INTEGRITY_VIOLATION", request);
    }

    // Last-resort fallback for anything unmapped — never let a raw exception message or
    // stack trace reach the client.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, resolve("error.internal"), "INTERNAL_ERROR", request);
    }
}
