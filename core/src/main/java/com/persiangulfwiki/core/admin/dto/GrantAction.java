package com.persiangulfwiki.core.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = GrantAction.VALUES_DOC)
public enum GrantAction {
    GRANT, REVOKE;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `GRANT`: gives the user the role (for EXPERT_REVIEWER, for the given `expertise`).
            - `REVOKE`: takes the role away.""";
}
