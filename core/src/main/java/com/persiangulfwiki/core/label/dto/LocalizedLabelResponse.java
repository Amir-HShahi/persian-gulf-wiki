package com.persiangulfwiki.core.label.dto;

// A label resolved for the reader. `language` is the language the value is actually in, which
// differs from the requested one exactly when isFallback is true (Farsi stood in for a missing
// English or Arabic label).
public record LocalizedLabelResponse(String value, String language, boolean isFallback) {
}
