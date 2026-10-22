package com.persiangulfwiki.core.topic.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.topic.dto.AdminTopicResponse;
import com.persiangulfwiki.core.topic.dto.CreateTopicRequest;
import com.persiangulfwiki.core.topic.dto.UpdateTopicRequest;
import com.persiangulfwiki.core.topic.service.TopicAdminService;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Admin-only, like AttributeAdminController, and not moderated: topics are site structure, not
// content. Every write is audit-logged by the service. Shares the "Topics" tag described on
// TopicController.
@RestController
@RequestMapping("/api/admin/topics")
@RequiredArgsConstructor
@Tag(name = "Topics")
@SecurityRequirement(name = "cookieAuth")
public class TopicAdminController {

    private final TopicAdminService topicAdminService;
    private final MessageSource messageSource;

    @Operation(summary = "List every topic for admins", description = "Admin only. Every topic in display order, "
            + "archived ones included, with its names in all three languages.")
    @ApiResponse(responseCode = "200", description = "Every topic.")
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin or its email address is not "
            + "yet verified; read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<AdminTopicResponse>> list() {
        return ApiResult.of(topicAdminService.list(), message("success.adminTopicsList"));
    }

    @Operation(summary = "Create a topic", description = "Admin only. `names.fa` is required; `names.en` and "
            + "`names.ar` are optional and can be added later through the labels endpoint with `referentKind` TOPIC. "
            + "`slug` is permanent. `icon` is a Lucide icon name; only its form is checked, so the client should show "
            + "a fallback icon for a name it doesn't know. An omitted `sortOrder` places the topic last.")
    @ApiResponse(responseCode = "201", description = "The created topic with its names.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation: `slug` is missing, longer than "
            + "60 characters or not a lower-case letter followed by lower-case letters, digits or hyphens; `icon` is "
            + "missing, longer than 64 characters or not lower-case kebab case; `color` is missing or not a `#RRGGBB` "
            + "hex code; `names` or `names.fa` is missing; or a name is blank or longer than 200 characters. `detail` "
            + "is the fixed summary \"validation failed\" and the failures are in the `errors` array, one entry per "
            + "field with `field` and `message`. A malformed body is also a 400, with `code` "
            + "HTTP_MESSAGE_NOT_READABLE.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A topic with this `slug` already exists, archived ones included.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<AdminTopicResponse> create(@Valid @RequestBody CreateTopicRequest request) {
        AdminTopicResponse data = topicAdminService.create(currentUserId(), request);
        return ApiResult.of(data, message("success.topicCreated"));
    }

    @Operation(summary = "Update a topic", description = "Admin only. Replaces `icon`, `color`, `sortOrder` and "
            + "`isArchived` rather than merging, so all four are required. Topics cannot be deleted; set `isArchived` "
            + "to retire one: it disappears from the public topic list and can no longer be added to an article, but "
            + "articles that already have it keep it. The slug cannot change, and names change through the labels "
            + "endpoint.")
    @ApiResponse(responseCode = "200", description = "The updated topic.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID; or a field failed "
            + "validation (`icon`, `color`, `sortOrder` or `isArchived` missing, `icon` too long or not kebab case, "
            + "`color` not a `#RRGGBB` hex code), with `detail` \"validation failed\" and the failures in the `errors` "
            + "array; or the body is malformed (`code` HTTP_MESSAGE_NOT_READABLE).",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No topic with this id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{topicId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<AdminTopicResponse> update(@PathVariable UUID topicId,
            @Valid @RequestBody UpdateTopicRequest request) {
        AdminTopicResponse data = topicAdminService.update(currentUserId(), topicId, request);
        return ApiResult.of(data, message("success.topicUpdated"));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
