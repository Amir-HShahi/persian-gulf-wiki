package com.persiangulfwiki.core.expertreviewer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = ReviewDecision.VALUES_DOC)
public enum ReviewDecision {
    APPROVE, REJECT;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `APPROVE`: accepts the application and gives the applicant the EXPERT_REVIEWER role for the expertise \
            applied for.
            - `REJECT`: declines the application.""";
}
