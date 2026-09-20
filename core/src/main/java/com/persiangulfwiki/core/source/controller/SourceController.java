package com.persiangulfwiki.core.source.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.source.dto.CreateSourceRequest;
import com.persiangulfwiki.core.source.dto.SourceResponse;
import com.persiangulfwiki.core.source.service.SourceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Deliberately NOT annotated @Validated. Spring applies method validation to
// constrained @RequestParam/@PathVariable parameters by itself, and reports a failure as
// the request-handling error the shared error advice already turns into a 400 with a
// per-field breakdown. Adding @Validated replaces that with an AOP proxy that raises a
// plain constraint-violation error instead, which nothing handles — so `size=101` comes
// back as a 500 rather than a 400. Verified by test: pageSizeAboveTheMaximumIsRejected.
@RestController
@RequestMapping("/api/sources")
@RequiredArgsConstructor
@Tag(name = "Sources", description = "The citation catalogue: the works that structured facts and article statements are "
        + "attributed to. Reading is open to everyone; any signed-in contributor may add one, "
        + "since citing a work is part of writing, not a moderation step.")
public class SourceController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SourceService sourceService;
    private final MessageSource messageSource;

    @Operation(summary = "Create a source", description = "Only `title` is required. A citation taken from a physical document often has no "
            + "web address, named publisher or printed date, and those fields stay empty rather "
            + "than forcing a placeholder. The creating account is recorded on the source.")
    @ApiResponse(responseCode = "201", description = "The created source.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation. `detail` is the fixed summary \"validation failed\" — "
            + "the actual failures are in the `errors` array, one entry per field with `field` and "
            + "`message`. `title` must be non-blank and at most 500 characters, `url` at most 2000, "
            + "`publisher` at most 500, and `publishedOn` must not be in the future.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account's email address is not yet verified, or the CSRF header is "
            + "missing or does not match the cookie — both surface as a plain 403; read `detail` "
            + "for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<SourceResponse> create(@Valid @RequestBody CreateSourceRequest request) {
        SourceResponse data = sourceService.create(currentUserId(), request);
        String message = messageSource.getMessage("success.sourceCreated", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Get a source by id", description = "Open to everyone; no account is required.")
    @ApiResponse(responseCode = "200", description = "The source.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No source with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{sourceId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SourceResponse> get(@PathVariable UUID sourceId) {
        SourceResponse data = sourceService.get(sourceId);
        String message = messageSource.getMessage("success.sourceFetched", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "List sources", description = "Open to everyone; no account is required.")
    @ApiResponse(responseCode = "200", description = "One page of sources. An out-of-range page is an empty list, not an error.")
    @ApiResponse(responseCode = "400", description = "`page` or `size` is outside its allowed range.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "page", description = "Zero-based page index.")
    @Parameter(name = "size", description = "Page size, 1 to 100.")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<SourceResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        List<SourceResponse> data = sourceService.list(PageRequest.of(page, size));
        String message = messageSource.getMessage("success.sourcesList", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
