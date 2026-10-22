package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// Geometries go back out as WKT, the same format a subject revision's payload accepts, so a
// client can round-trip a subject without a second representation to reconcile.
//
// Detail fields are null for every kind that doesn't define them — a PORT response carries
// `location` and leaves `area`/`habitat`/`boundary` null. Carrying all of them keeps
// one response type for one resource instead of near-identical per-kind ones the caller would
// have to switch on before it can read anything.
public record SubjectResponse(
        UUID id,
        SubjectKind kind,
        Instant createdAt,
        String location,
        String area,
        String habitat,
        String boundary,
        @Schema(description = "Set when the id asked for belongs to a subject that was merged into this one: that "
                + "id. The client should switch to this subject's `id`. Null otherwise.", nullable = true)
        @Nullable UUID redirectedFrom) {
}
