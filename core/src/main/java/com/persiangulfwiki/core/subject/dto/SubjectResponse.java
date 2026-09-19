package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Geometries go back out as WKT, the same format CreateSubjectRequest accepts, so a client
// can round-trip a subject without a second representation to reconcile.
//
// Detail fields are null for every kind that doesn't define them — a PORT response carries
// `location` and leaves `areaKm2`/`area`/`habitat` null. Carrying all four keeps one
// response type for one resource instead of four near-identical ones the caller would have
// to switch on before it can read anything.
public record SubjectResponse(
        UUID id,
        SubjectKind kind,
        Instant createdAt,
        BigDecimal areaKm2,
        String location,
        String area,
        String habitat) {
}
