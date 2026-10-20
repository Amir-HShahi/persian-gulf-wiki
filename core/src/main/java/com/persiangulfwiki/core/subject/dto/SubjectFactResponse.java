package com.persiangulfwiki.core.subject.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record SubjectFactResponse(@Nullable String attribute, @Nullable List<SubjectFactValueResponse> values) {
}
