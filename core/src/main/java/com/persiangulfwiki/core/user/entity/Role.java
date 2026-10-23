package com.persiangulfwiki.core.user.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = Role.VALUES_DOC)
public enum Role {
    CONTRIBUTOR, MODERATOR, EXPERT_REVIEWER, ADMIN;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `CONTRIBUTOR`: every registered account has it; can write articles and propose subjects and media, which \
            a moderator then reviews.
            - `MODERATOR`: reviews submitted articles, subjects and media and decides whether they are published.
            - `EXPERT_REVIEWER`: a subject-matter expert for one subject kind (`expertise`); marks published content \
            as correct, without blocking it.
            - `ADMIN`: manages users and roles, the field schema and labels.""";
}
