package com.persiangulfwiki.core.attribute.controller;

import com.persiangulfwiki.core.attribute.dto.AttributeDefinitionResponse;
import com.persiangulfwiki.core.attribute.dto.AttributeGroupResponse;
import com.persiangulfwiki.core.attribute.dto.AttributeOptionResponse;
import com.persiangulfwiki.core.attribute.dto.CreateAttributeDefinitionRequest;
import com.persiangulfwiki.core.attribute.dto.CreateAttributeGroupRequest;
import com.persiangulfwiki.core.attribute.dto.CreateAttributeOptionRequest;
import com.persiangulfwiki.core.attribute.dto.UpdateAttributeDefinitionRequest;
import com.persiangulfwiki.core.attribute.dto.UpdateAttributeGroupRequest;
import com.persiangulfwiki.core.attribute.dto.UpdateAttributeOptionRequest;
import com.persiangulfwiki.core.attribute.service.AttributeDefinitionService;
import com.persiangulfwiki.core.attribute.service.AttributeGroupService;
import com.persiangulfwiki.core.common.dto.ApiResult;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Admin-only, like AdminController, and deliberately not moderated: the field vocabulary is
// schema, not content. Every write is audit-logged by the service.
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Attributes", description = "The field vocabulary behind subject infoboxes and listing filters: groups (headings "
        + "such as Geography), field definitions (what a subject of a given kind can record, and how) and the allowed "
        + "values of choice fields. Facts are stored as permanent codes — a group's or field's `key`, an option's "
        + "`code` — and every displayed name is a label in the reader's language. Codes can never be renamed or "
        + "deleted; a field or option is retired by deprecating it. Reading a kind's vocabulary is open to everyone; "
        + "changing it requires an admin account.")
@SecurityRequirement(name = "cookieAuth")
public class AttributeAdminController {

    private final AttributeGroupService attributeGroupService;
    private final AttributeDefinitionService attributeDefinitionService;
    private final MessageSource messageSource;

    @Operation(summary = "Create a field group", description = "Admin only. `labels.fa` is required; `labels.en` and "
            + "`labels.ar` are optional and can be added later through the labels endpoint. `key` is permanent.")
    @ApiResponse(responseCode = "201", description = "The created group with its labels.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation: `key` is missing, longer than 60 "
            + "characters or not lower snake case (a letter, then letters, digits or underscores); `labels` or "
            + "`labels.fa` is missing; or a label is blank or longer than 200 characters. `detail` is the fixed summary "
            + "\"validation failed\" and the failures are in the `errors` array, one entry per field with `field` and "
            + "`message`. A malformed body is also a 400, with `code` HTTP_MESSAGE_NOT_READABLE.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A group with this `key` already exists.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/attribute-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<AttributeGroupResponse> createGroup(@Valid @RequestBody CreateAttributeGroupRequest request) {
        AttributeGroupResponse data = attributeGroupService.create(currentUserId(), request);
        return ApiResult.of(data, message("success.attributeGroupCreated"));
    }

    @Operation(summary = "Update a field group", description = "Admin only. Replaces the group's mutable fields — only "
            + "`sort` — rather than merging; an omitted `sort` becomes 0. The key cannot change, and labels change "
            + "through the labels endpoint.")
    @ApiResponse(responseCode = "200", description = "The updated group.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID, or the body is malformed "
            + "(`code` HTTP_MESSAGE_NOT_READABLE).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No group with this id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/attribute-groups/{groupId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<AttributeGroupResponse> updateGroup(@PathVariable UUID groupId,
            @Valid @RequestBody UpdateAttributeGroupRequest request) {
        AttributeGroupResponse data = attributeGroupService.update(currentUserId(), groupId, request);
        return ApiResult.of(data, message("success.attributeGroupUpdated"));
    }

    @Operation(summary = "Create a field definition", description = "Admin only. Defines a field that subjects of the "
            + "listed `kinds` can record. Type-dependent rules: `refKind` (the kind a referenced subject must be) is "
            + "required for SUBJECT_REF and not allowed otherwise; `unit`, `minValue` and `maxValue` are only allowed "
            + "for NUMBER, and `minValue` must not exceed `maxValue`; `isFilterable` is not allowed for PLAIN_TEXT or "
            + "LOCALIZED_TEXT. `unit` is a lower-case unit code the client localizes, e.g. `cm` or `km2`. Omitted "
            + "flags are false and an omitted `sort` is 0. `labels.fa` is required. Choice values for an ENUM field "
            + "are added afterwards through the options endpoint. `key` is permanent.")
    @ApiResponse(responseCode = "201", description = "The created definition with its kinds and labels.")
    @ApiResponse(responseCode = "400", description = "Request failed field validation: `key` is missing, too long or not "
            + "lower snake case; `valueType`, `groupId`, `kinds` or `labels.fa` is missing; `unit` is not a lower-case "
            + "code; a label is blank or too long; or a type-dependent rule above is broken, reported against the field "
            + "to change (`refKind`, `unit`, `minValue`, `maxValue` or `isFilterable`). `detail` is the fixed summary "
            + "\"validation failed\" and the failures are in the `errors` array. A `valueType`, `refKind` or kind value "
            + "outside its allowed set, or a malformed body, is also a 400 but with `code` HTTP_MESSAGE_NOT_READABLE "
            + "and no `errors` array.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No group with the given `groupId`.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "A definition with this `key` already exists.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/attribute-definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<AttributeDefinitionResponse> createDefinition(
            @Valid @RequestBody CreateAttributeDefinitionRequest request) {
        AttributeDefinitionResponse data = attributeDefinitionService.create(currentUserId(), request);
        return ApiResult.of(data, message("success.attributeDefinitionCreated"));
    }

    @Operation(summary = "Update a field definition", description = "Admin only. Replaces every mutable field rather than "
            + "merging: an omitted `unit`, `refKind`, `minValue` or `maxValue` is cleared, an omitted flag is false and "
            + "an omitted `sort` is 0. Same type-dependent rules as on create. Set `isDeprecated` to retire the field. "
            + "The key cannot change, and labels change through the labels endpoint. An ENUM field that already has "
            + "choice values cannot change to another type.")
    @ApiResponse(responseCode = "200", description = "The updated definition.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID, or the body fails the same "
            + "validation as on create (`errors` array), or is malformed (`code` HTTP_MESSAGE_NOT_READABLE).",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No definition with this id, or no group with the given `groupId`.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The field is an ENUM with choice values and `valueType` would change it "
            + "to another type.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/attribute-definitions/{definitionId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<AttributeDefinitionResponse> updateDefinition(@PathVariable UUID definitionId,
            @Valid @RequestBody UpdateAttributeDefinitionRequest request) {
        AttributeDefinitionResponse data = attributeDefinitionService.update(currentUserId(), definitionId, request);
        return ApiResult.of(data, message("success.attributeDefinitionUpdated"));
    }

    @Operation(summary = "Add a choice value to a field", description = "Admin only, and only for an ENUM field. `code` is "
            + "permanent and upper case — letters, digits and underscores, e.g. `CORAL_REEF`, or a standard code such "
            + "as an ISO 3166 country code. `labels.fa` is required; an omitted `sort` is 0.")
    @ApiResponse(responseCode = "201", description = "The created choice value with its labels.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID; `code` is missing, longer "
            + "than 60 characters or not upper case; `labels` or `labels.fa` is missing; or a label is blank or too "
            + "long (`errors` array). A malformed body has `code` HTTP_MESSAGE_NOT_READABLE.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No definition with this id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The field is not an ENUM, or it already has a choice value with this "
            + "`code`.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/attribute-definitions/{definitionId}/options")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<AttributeOptionResponse> createOption(@PathVariable UUID definitionId,
            @Valid @RequestBody CreateAttributeOptionRequest request) {
        AttributeOptionResponse data = attributeDefinitionService.createOption(currentUserId(), definitionId, request);
        return ApiResult.of(data, message("success.attributeOptionCreated"));
    }

    @Operation(summary = "Update a choice value", description = "Admin only. Replaces `sort` and `isDeprecated` rather "
            + "than merging; omitted, they become 0 and false. Set `isDeprecated` to retire the value: subjects that "
            + "already hold it keep it, but it can no longer be newly chosen. The code cannot change, and labels "
            + "change through the labels endpoint.")
    @ApiResponse(responseCode = "200", description = "The updated choice value.")
    @ApiResponse(responseCode = "400", description = "An id in the path is not a valid UUID, or the body is malformed "
            + "(`code` HTTP_MESSAGE_NOT_READABLE).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account is not an admin, its email address is not yet "
            + "verified, or the CSRF header is missing or does not match the cookie; read `detail` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No definition with this id, or it has no choice value with this id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN cookie, "
            + "then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie "
            + "value here is rejected.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/attribute-definitions/{definitionId}/options/{optionId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<AttributeOptionResponse> updateOption(@PathVariable UUID definitionId, @PathVariable UUID optionId,
            @Valid @RequestBody UpdateAttributeOptionRequest request) {
        AttributeOptionResponse data =
                attributeDefinitionService.updateOption(currentUserId(), definitionId, optionId, request);
        return ApiResult.of(data, message("success.attributeOptionUpdated"));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
