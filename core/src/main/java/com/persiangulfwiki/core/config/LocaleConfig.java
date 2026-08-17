package com.persiangulfwiki.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

// Defining this bean overrides Boot's own AcceptHeaderLocaleResolver autoconfiguration, which
// has no way to restrict the resolved locale to a supported list — without it, an
// Accept-Language we don't ship a bundle for (e.g. "de") would resolve as-is and only fall back
// to the base bundle per-key, instead of cleanly resolving to a supported locale up front.
@Configuration
public class LocaleConfig {

    private static final Locale FARSI = Locale.forLanguageTag("fa");
    private static final Locale ARABIC = Locale.forLanguageTag("ar");

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(FARSI);
        resolver.setSupportedLocales(List.of(FARSI, Locale.ENGLISH, ARABIC));
        return resolver;
    }
}
