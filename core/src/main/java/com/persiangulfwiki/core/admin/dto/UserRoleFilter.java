package com.persiangulfwiki.core.admin.dto;

public enum UserRoleFilter {
    ADMIN, MODERATOR, EXPERT_REVIEWER, CONTRIBUTOR;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `ADMIN`: users holding the ADMIN role.
            - `MODERATOR`: users holding the MODERATOR role.
            - `EXPERT_REVIEWER`: users holding the EXPERT_REVIEWER role, for any expertise.
            - `CONTRIBUTOR`: users with no role besides CONTRIBUTOR. Every account holds CONTRIBUTOR, so this \
            matches only those without any other role.""";
}
