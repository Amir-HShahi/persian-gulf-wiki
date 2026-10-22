package com.persiangulfwiki.core.attribute.controller;

import com.persiangulfwiki.core.attribute.dto.SubjectKindAttributesResponse;
import com.persiangulfwiki.core.attribute.service.AttributeVocabularyService;
import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

// Public read, permitted for anonymous GET by PublicRoutes.CONTENT_READS.
@RestController
@RequestMapping("/api/subject-kinds")
@RequiredArgsConstructor
@Tag(name = "Attributes")
public class SubjectKindAttributeController {

    private final AttributeVocabularyService attributeVocabularyService;
    private final MessageSource messageSource;

    @Operation(summary = "Get a kind's fields", description = "Open to everyone; no account is required. Returns the "
            + "field groups, field definitions and choice values that apply to the kind, with every label in the "
            + "language picked from `Accept-Language` (fa, en or ar; anything else gets fa). A missing English or "
            + "Arabic label falls back to the Farsi one, with `isFallback` true and `language` \"fa\"; a label is null "
            + "only if no Farsi label exists either. Groups are ordered by `sort`, fields by `sort` within their group "
            + "and choice values by `sort`. Deprecated fields and choice values are included with `isDeprecated` true: "
            + "show a value a subject already holds, but don't offer it for new ones. Groups with no field for this "
            + "kind are left out. This is what the subject edit form and the listing filter panel are built from.")
    @ApiResponse(responseCode = "200", description = "The kind's label and its field vocabulary.")
    @ApiResponse(responseCode = "400", description = "`kind` is not one of ISLAND, PORT, OIL_FIELD, SPECIES, STRAIT, "
            + "FISHING_GROUND, PROVINCE (case-insensitive).", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "kind", description = "Case-insensitive.\n\n" + SubjectKind.VALUES_DOC)
    @GetMapping("/{kind}/attributes")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<SubjectKindAttributesResponse> listAttributes(@PathVariable String kind) {
        Locale locale = LocaleContextHolder.getLocale();
        SubjectKindAttributesResponse data =
                attributeVocabularyService.listForKind(kind, SupportedLocales.normalize(locale).getLanguage());
        String message = messageSource.getMessage("success.subjectKindAttributesFetched", null, locale);
        return ApiResult.of(data, message);
    }
}
