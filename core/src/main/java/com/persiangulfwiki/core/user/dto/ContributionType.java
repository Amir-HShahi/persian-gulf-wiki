package com.persiangulfwiki.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = ContributionType.VALUES_DOC)
public enum ContributionType {
    CREATED,
    REVISION,
    TRANSLATION,
    MEDIA;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `CREATED`: the user started this article.
            - `REVISION`: at least one of the user's edits to this article was approved.
            - `TRANSLATION`: the user wrote the first approved version of one of the article's translations \
            (not counting the language the article was created in).
            - `MEDIA`: at least one image, video or panorama the user uploaded to this article is published.""";
}
