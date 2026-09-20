package com.persiangulfwiki.core.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

// Only `title` is required. A citation is frequently entered from a physical document with no
// URL, no named publisher and no printed date, and rejecting those would push contributors
// into inventing values to get past validation — which is worse than a sparse row.
public record CreateSourceRequest(
        @NotBlank(message = "{validation.sourceTitle.required}") @Size(max = 500, message = "{validation.sourceTitle.tooLong}") String title,
        @Size(max = 2000, message = "{validation.sourceUrl.tooLong}") String url,
        @Size(max = 500, message = "{validation.sourcePublisher.tooLong}") String publisher,
        @PastOrPresent(message = "{validation.sourcePublishedOn.notFuture}") LocalDate publishedOn) {
}
