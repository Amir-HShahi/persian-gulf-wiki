package com.persiangulfwiki.core.subject.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record SubjectAliasesResponse(@Nullable List<String> fa, @Nullable List<String> en,
        @Nullable List<String> ar) {
}
