package com.persiangulfwiki.core.label.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.label.dto.LabelResponse;
import com.persiangulfwiki.core.label.dto.UpsertLabelRequest;
import com.persiangulfwiki.core.label.service.LabelService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// The path segments are taken as raw strings and parsed in LabelService, so every malformed
// combination is one translated 400 rather than a mix of conversion failures.
@RestController
@RequestMapping("/api/admin/labels")
@RequiredArgsConstructor
@Tag(name = "Labels", description = "Display names of the field vocabulary and of topics in Farsi, English and Arabic. Farsi is "
        + "canonical; a missing English or Arabic name falls back to it wherever names are read. Subject names are "
        + "not edited here — they change only through a moderated subject revision.")
@SecurityRequirement(name = "cookieAuth")
public class LabelAdminController {

    private final LabelService labelService;
    private final MessageSource messageSource;

    @Operation(summary = "Set a label", description = "Admin only. Creates or replaces the name of a field group, field "
            + "definition, choice value, subject kind or topic in one language. Labels cannot be deleted, so a group, "
            + "field, choice value or topic always keeps its Farsi name.")
    @ApiResponse(responseCode = "200", description = "The stored label.")
    @ApiResponse(responseCode = "400", description = "One of: `referentKind` is not GROUP, ATTRIBUTE, OPTION, KIND "
            + "or TOPIC (SUBJECT is refused), or `referentId` is not a UUID — or, for KIND, not one of ISLAND, PORT, OIL_FIELD, "
            + "SPECIES, STRAIT, FISHING_GROUND, PROVINCE — with `code` INVALID_LABEL_REFERENT; `language` is not fa, "
            + "en or ar, with `code` UNSUPPORTED_LABEL_LANGUAGE; or `value` is blank or longer than 200 characters, "
            + "with `detail` \"validation failed\" and the failure in the `errors` array.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No group, definition, choice value or topic with this id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "referentKind", description = "What is being named: GROUP, ATTRIBUTE, OPTION, KIND or TOPIC "
            + "(case-insensitive).")
    @Parameter(name = "referentId", description = "The group's, definition's, choice value's or topic's id; for KIND, the kind "
            + "itself, e.g. ISLAND (case-insensitive).")
    @Parameter(name = "language", description = "fa, en or ar (case-insensitive).")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{referentKind}/{referentId}/{language}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<LabelResponse> upsert(@PathVariable String referentKind, @PathVariable String referentId,
            @PathVariable String language, @Valid @RequestBody UpsertLabelRequest request) {
        LabelResponse data = labelService.upsert(currentUserId(), referentKind, referentId, language, request.value());
        String message = messageSource.getMessage("success.labelSaved", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
