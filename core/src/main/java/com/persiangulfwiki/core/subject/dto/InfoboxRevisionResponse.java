package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record InfoboxRevisionResponse(
        @Schema(description = "Number of the published revision the infobox shows.") int number,
        SubjectVerificationResponse verification) {
}
