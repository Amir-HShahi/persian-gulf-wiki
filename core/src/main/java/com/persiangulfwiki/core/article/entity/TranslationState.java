package com.persiangulfwiki.core.article.entity;

// UP_TO_DATE: this translation's content matches what its sourceRevisionId pointed at when
// last synced (or it is the canonical translation itself, which is trivially in sync with
// itself). OUTDATED: the canonical translation has moved on since this one's sourceRevisionId
// was set -- Phase 4's translation-sync tooling is what would ever flip a translation into
// this state, not Phase 2. INDEPENDENT: this translation has deliberately diverged from the
// canonical text (e.g. locally-relevant detail with no equivalent to translate) and is exempt
// from the outdated check entirely.
public enum TranslationState {
    UP_TO_DATE, OUTDATED, INDEPENDENT
}
