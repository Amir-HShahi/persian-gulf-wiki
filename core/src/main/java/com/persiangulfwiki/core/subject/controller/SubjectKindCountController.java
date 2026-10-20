package com.persiangulfwiki.core.subject.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.subject.dto.SubjectKindCountResponse;
import com.persiangulfwiki.core.subject.service.SubjectListingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

// Public read, permitted for anonymous GET by PublicRoutes.CONTENT_READS (/api/subject-kinds/**).
@RestController
@RequestMapping("/api/subject-kinds")
@RequiredArgsConstructor
@Tag(name = "Subjects")
public class SubjectKindCountController {

    private final SubjectListingService subjectListingService;
    private final MessageSource messageSource;

    @Operation(summary = "Count subjects per kind", description = "Open to everyone; no account is required. One "
            + "entry per kind (ISLAND, PORT, OIL_FIELD, SPECIES, STRAIT, FISHING_GROUND, PROVINCE), kinds with no "
            + "subjects included with count 0. Names are in the language picked from `Accept-Language` (fa, en or "
            + "ar; anything else gets fa), falling back to Farsi with `isFallback` true. For the home page's "
            + "headline numbers.")
    @ApiResponse(responseCode = "200", description = "A count for every kind.")
    @GetMapping("/counts")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<SubjectKindCountResponse>> counts() {
        Locale locale = LocaleContextHolder.getLocale();
        List<SubjectKindCountResponse> data =
                subjectListingService.kindCounts(SupportedLocales.normalize(locale).getLanguage());
        String message = messageSource.getMessage("success.subjectKindCounts", null, locale);
        return ApiResult.of(data, message);
    }
}
