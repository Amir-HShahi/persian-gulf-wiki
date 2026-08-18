package com.persiangulfwiki.core.common.exception;

// New base class, added alongside NotFoundException/ConflictException/BadRequestException:
// until Phase 2, every domain-thrown (non-@PreAuthorize) 403 case was handled by Spring
// Security's own AccessDeniedException via GlobalExceptionHandler.handleAccessDenied.
// NotRevisionAuthorException needs the same 403 shape for a plain ownership check that has
// nothing to do with Spring Security's authorization machinery, so it gets a dedicated base
// rather than either misusing AccessDeniedException (a Spring Security type, not a domain
// one) or being left as a raw RuntimeException with no status mapping.
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
