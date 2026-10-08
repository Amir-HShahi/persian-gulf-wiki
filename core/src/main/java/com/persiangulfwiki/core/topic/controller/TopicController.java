package com.persiangulfwiki.core.topic.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.topic.dto.TopicResponse;
import com.persiangulfwiki.core.topic.service.TopicService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

// Public read, permitted for anonymous GET by PublicRoutes.CONTENT_READS. Not @Validated, for the
// reason on ArticleController.
@RestController
@RequestMapping("/api/topics")
@RequiredArgsConstructor
@Tag(name = "Topics", description = "What articles are about (marine life, history, ...) -- the home page's "
        + "categories. An article can have several. Its topics are set on its original-language text and apply "
        + "to every translation. Admins create topics and edit their Lucide icon, color and order; topics are never "
        + "deleted, only archived. Reading the public list is open to everyone.")
public class TopicController {

    private final TopicService topicService;
    private final MessageSource messageSource;

    @Operation(summary = "List topics with article counts", description = "Open to everyone; no account is required. "
            + "Every topic that is not archived, in display order, with how many articles about it are published in "
            + "`language`. An article counts once it has an approved text in `language` and its approved "
            + "original-language text names the topic. Names are in the language picked from `Accept-Language` (fa, "
            + "en or ar; anything else gets fa); a missing English or Arabic name falls back to the Farsi one, with `isFallback` true.")
    @ApiResponse(responseCode = "200", description = "Every topic that is not archived, zero counts included.")
    @ApiResponse(responseCode = "400", description = "`language` is missing, blank or longer than 20 characters.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "language", required = true, description = "Language of the articles to count, e.g. \"fa\", "
            + "\"en\", \"ar\". Matched exactly, as in the article list.")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<TopicResponse>> list(
            @RequestParam @NotBlank(message = "{validation.language.required}")
                    @Size(max = 20, message = "{validation.language.tooLong}") String language) {
        Locale locale = LocaleContextHolder.getLocale();
        List<TopicResponse> data = topicService.list(language, SupportedLocales.normalize(locale).getLanguage());
        String message = messageSource.getMessage("success.topicsList", null, locale);
        return ApiResult.of(data, message);
    }
}
