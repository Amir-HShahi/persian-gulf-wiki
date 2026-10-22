package com.persiangulfwiki.core.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = UserStatusAction.VALUES_DOC)
public enum UserStatusAction {
    SUSPEND, BAN, REINSTATE;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `SUSPEND`: disables the account; the user can no longer sign in.
            - `BAN`: disables the account exactly like SUSPEND; only the audit record differs.
            - `REINSTATE`: re-enables a suspended or banned account.""";
}
