package com.persiangulfwiki.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

// Registered at HIGHEST_PRECEDENCE so it runs before Spring Security's own filter chain
// (registered at order -100) and therefore before every other place an error response can
// originate: the AuthenticationEntryPoint, the two pre-dispatch security filters, and
// GlobalExceptionHandler. That guarantees a traceId is already on the MDC for
// ProblemDetails.of() no matter which of those builds the body.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        MDC.put(ProblemDetails.TRACE_ID_MDC_KEY, UUID.randomUUID().toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(ProblemDetails.TRACE_ID_MDC_KEY);
        }
    }
}
