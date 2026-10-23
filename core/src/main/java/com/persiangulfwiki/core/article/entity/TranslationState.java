package com.persiangulfwiki.core.article.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// UP_TO_DATE: this translation's content matches what its sourceRevisionId pointed at when
// last synced (or it is the canonical translation itself, which is trivially in sync with
// itself). OUTDATED: the canonical translation has moved on since this one's sourceRevisionId
// was set -- Phase 4's translation-sync tooling is what would ever flip a translation into
// this state, not Phase 2. INDEPENDENT: this translation has deliberately diverged from the
// canonical text (e.g. locally-relevant detail with no equivalent to translate) and is exempt
// from the outdated check entirely.
@Schema(description = TranslationState.VALUES_DOC)
public enum TranslationState {
    UP_TO_DATE, OUTDATED, INDEPENDENT;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `UP_TO_DATE`: matches the original-language text it was translated from. The original-language \
            translation itself is always UP_TO_DATE.
            - `OUTDATED`: the original-language text has changed since; this translation needs updating.
            - `INDEPENDENT`: deliberately differs from the original, e.g. with local detail, and is never marked \
            outdated.""";
}
