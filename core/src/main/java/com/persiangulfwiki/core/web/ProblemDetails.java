package com.persiangulfwiki.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;

// Every error response in the app — whether built by GlobalExceptionHandler, a security
// filter running ahead of the DispatcherServlet, or the AuthenticationEntryPoint — goes
// through this so the body shape never drifts between call sites: RFC 7807's own fields
// (type/title/status/detail) plus instance, and the extension properties code/timestamp/
// traceId, are always present together.
public final class ProblemDetails {

    public static final String TRACE_ID_MDC_KEY = "traceId";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String detail, String code, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", code);
        problemDetail.setProperty("timestamp", Instant.now());
        problemDetail.setProperty("traceId", MDC.get(TRACE_ID_MDC_KEY));
        return problemDetail;
    }
}
