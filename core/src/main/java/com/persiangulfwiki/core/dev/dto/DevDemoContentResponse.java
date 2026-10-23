package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

public record DevDemoContentResponse(
        @Schema(description = "Demo accounts created. They share the documented dev password.")
        int users,
        int sources,
        @Schema(description = "Subjects created, per kind.")
        Map<SubjectKind, Integer> subjectsByKind,
        int articles,
        @Schema(description = "Article translations created: one per article and language (fa, en, ar).")
        int translations,
        @Schema(description = "Article revisions created, across every translation's history.")
        int revisions,
        @Schema(description = "Revisions left awaiting a moderator, each with an open moderation task.")
        int pendingRevisions,
        @Schema(description = "Published gallery images, every one with all its size variants stored.")
        int images,
        @Schema(description = "Published 360° panoramas.")
        int panoramas,
        int expertVerifications,
        int featuredWindows,
        @Schema(description = "How long the seed took, in milliseconds.")
        long durationMs) {
}
