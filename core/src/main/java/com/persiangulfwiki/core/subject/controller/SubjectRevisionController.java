package com.persiangulfwiki.core.subject.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.subject.dto.CreateSubjectProposalRequest;
import com.persiangulfwiki.core.subject.dto.SubjectRevisionResponse;
import com.persiangulfwiki.core.subject.dto.UpdateSubjectRevisionRequest;
import com.persiangulfwiki.core.subject.service.SubjectRevisionService;

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

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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

// No hasRole(...) on any write, like ArticleController and for the same reason: moderation is
// what gates publication, so any verified account may propose or edit. Not annotated @Validated --
// see SubjectController.
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Subject Revisions", description = "How a subject's names, aliases, facts, geometry and main image change. "
        + "Every change is a revision that a moderator approves: a revision with no subject yet proposes a new "
        + "subject, and approving it creates one; a revision of an existing subject starts as a copy of what "
        + "readers see now. Facts are checked against the kind's fields (GET /api/subject-kinds/{kind}/attributes). "
        + "Any signed-in, verified account may propose and edit; only the revision's author may change or submit "
        + "it. Approved revisions are public; any other revision is visible only to its author and to moderators "
        + "-- to anyone else it looks like something that does not exist (404). When the payload breaks a rule, "
        + "the 400 lists every failure in `errors`, each with the path of the offending field, e.g. "
        + "`payload.facts[2].values[0].sourceId`.")
public class SubjectRevisionController {

    private static final String MODERATOR_AUTHORITY = "ROLE_MODERATOR";

    private static final String CSRF_DESCRIPTION = "CSRF token. Call GET /api/auth/csrf first to receive the "
            + "XSRF-TOKEN cookie, then encode its value and send the encoded result in this header — see the API "
            + "description above for the required encoding algorithm; sending the raw cookie value here is rejected.";

    private final SubjectRevisionService subjectRevisionService;
    private final MessageSource messageSource;
    private final RoleHierarchy roleHierarchy;

    @Operation(summary = "Propose a new subject", description = "Saves a draft proposal: the kind and everything the new "
            + "subject should say about itself. Nothing is created until a moderator approves the submitted proposal. "
            + "`payload.mainImageMediaId` must be left out, since a subject that does not exist yet has no articles "
            + "to take an image from.")
    @ApiResponse(responseCode = "201", description = "The proposal, as a DRAFT revision with no subject.")
    @ApiResponse(responseCode = "400", description = "Field validation failed (see `errors`): `kind` is missing or unknown, "
            + "`payload` is missing, `summary` is longer than 2000 characters, or the payload breaks a rule of the "
            + "kind's fields -- one entry per failure, each naming the field's path.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account's email address is not yet verified, or the CSRF header is "
            + "missing or does not match the cookie; read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = CSRF_DESCRIPTION)
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/subject-revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<SubjectRevisionResponse> propose(@Valid @RequestBody CreateSubjectProposalRequest request) {
        SubjectRevisionResponse data = subjectRevisionService.propose(currentUserId(), request);
        return ApiResult.of(data, message("success.subjectProposalCreated"));
    }

    @Operation(summary = "Start editing a subject", description = "Creates a draft revision of the subject as a copy of what "
            + "readers see now (its live revision), with `baseRevisionId` naming that revision. Change it with PATCH, "
            + "then submit it. If another edit is approved first, this one can no longer be submitted or approved and "
            + "a new edit has to be started. No request body.")
    @ApiResponse(responseCode = "201", description = "The new DRAFT revision.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Either the account's email address is not yet verified, or the CSRF header is "
            + "missing or does not match the cookie; read `detail` for which one occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = CSRF_DESCRIPTION)
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/subjects/{subjectId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<SubjectRevisionResponse> startEdit(@PathVariable UUID subjectId) {
        SubjectRevisionResponse data = subjectRevisionService.startEdit(currentUserId(), subjectId);
        return ApiResult.of(data, message("success.subjectRevisionCreated"));
    }

    @Operation(summary = "List a subject's revisions", description = "Open to everyone; oldest first. Approved revisions are "
            + "listed for every caller; the others only for their author and for moderators, and are simply left out "
            + "for anyone else.")
    @ApiResponse(responseCode = "200", description = "The revisions the caller may see.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No subject with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/subjects/{subjectId}/revisions")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<SubjectRevisionResponse>> list(@PathVariable UUID subjectId) {
        List<SubjectRevisionResponse> data =
                subjectRevisionService.list(subjectId, currentUserIdOrNull(), isCurrentUserModerator());
        return ApiResult.of(data, message("success.subjectRevisionsList"));
    }

    @Operation(summary = "Get a subject revision", description = "Open to everyone for an approved revision. Any other "
            + "revision -- including a proposal, which belongs to no subject until approved -- is readable only by its "
            + "author and by moderators.")
    @ApiResponse(responseCode = "200", description = "The revision.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No revision with that id, or one the caller may not read -- the two are "
            + "deliberately indistinguishable.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/subject-revisions/{revisionId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectRevisionResponse> get(@PathVariable UUID revisionId) {
        SubjectRevisionResponse data =
                subjectRevisionService.get(revisionId, currentUserIdOrNull(), isCurrentUserModerator());
        return ApiResult.of(data, message("success.subjectRevisionFetched"));
    }

    @Operation(summary = "Update a draft subject revision", description = "Replaces `payload` and `summary` in place; omitting "
            + "`summary` clears it. Only the author may update, and only while the revision is DRAFT or "
            + "CHANGES_REQUESTED.")
    @ApiResponse(responseCode = "200", description = "The updated revision.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID, or field validation failed (see "
            + "`errors`): `payload` is missing, `summary` is too long, or the payload breaks a rule of the kind's "
            + "fields.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The caller is not the revision's author, the account's email address is not "
            + "yet verified, or the CSRF header is missing or does not match the cookie; read `code` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No revision with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The revision is not DRAFT or CHANGES_REQUESTED, so it can no longer be "
            + "changed.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = CSRF_DESCRIPTION)
    @SecurityRequirement(name = "cookieAuth")
    @PatchMapping("/subject-revisions/{revisionId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectRevisionResponse> update(@PathVariable UUID revisionId,
            @Valid @RequestBody UpdateSubjectRevisionRequest request) {
        SubjectRevisionResponse data = subjectRevisionService.update(currentUserId(), revisionId, request);
        return ApiResult.of(data, message("success.subjectRevisionUpdated"));
    }

    @Operation(summary = "Submit a subject revision for review", description = "Moves the revision from DRAFT or "
            + "CHANGES_REQUESTED to PENDING and puts it in the moderators' queue. The payload is checked again, since "
            + "the kind's fields may have changed since it was saved. Only the author may submit.")
    @ApiResponse(responseCode = "200", description = "The revision, now PENDING.")
    @ApiResponse(responseCode = "400", description = "The id in the path is not a valid UUID, or the payload no longer fits the "
            + "kind's fields (see `errors`).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Access token cookie missing, invalid, or expired.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "The caller is not the revision's author, the account's email address is not "
            + "yet verified, or the CSRF header is missing or does not match the cookie; read `code` for which one "
            + "occurred.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No revision with that id.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "One of: the revision is not DRAFT or CHANGES_REQUESTED (code "
            + "SUBJECT_REVISION_NOT_EDITABLE); or it is an edit whose base is no longer the revision readers see, "
            + "because another edit was approved meanwhile (code STALE_SUBJECT_REVISION) -- start a new edit from the "
            + "current revision.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = CSRF_DESCRIPTION)
    @SecurityRequirement(name = "cookieAuth")
    @PostMapping("/subject-revisions/{revisionId}/submit")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectRevisionResponse> submit(@PathVariable UUID revisionId) {
        SubjectRevisionResponse data = subjectRevisionService.submit(currentUserId(), revisionId);
        return ApiResult.of(data, message("success.subjectRevisionSubmitted"));
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }

    // The two GET routes are public, so the caller may be anonymous -- see ArticleController.
    private @Nullable UUID currentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return UUID.fromString(authentication.getName());
    }

    // Through the RoleHierarchy, so ADMIN counts as a moderator -- see ArticleController.
    private boolean isCurrentUserModerator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
                .anyMatch(authority -> MODERATOR_AUTHORITY.equals(authority.getAuthority()));
    }
}
