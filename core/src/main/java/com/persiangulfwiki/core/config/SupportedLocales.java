package com.persiangulfwiki.core.config;

import java.util.List;
import java.util.Locale;

// Single source of truth for the locales we ship message bundles for. Used both by the
// request-scoped LocaleResolver and by EmailService, which has to normalize a locale that
// may have come from a non-dispatcher thread (e.g. the OAuth2 filter chain) and therefore
// isn't guaranteed to be one of ours.
public final class SupportedLocales {

    public static final Locale FARSI = Locale.forLanguageTag("fa");
    public static final Locale ARABIC = Locale.forLanguageTag("ar");
    public static final Locale DEFAULT = FARSI;

    public static final List<Locale> ALL = List.of(FARSI, Locale.ENGLISH, ARABIC);

    private SupportedLocales() {}

    /** Returns {@link #DEFAULT} for null or any locale we don't ship a bundle for; matches on language only. */
    public static Locale normalize(Locale locale) {
        if (locale == null) {
            return DEFAULT;
        }
        return ALL.stream()
                .filter(supported -> supported.getLanguage().equals(locale.getLanguage()))
                .findFirst()
                .orElse(DEFAULT);
    }
}
