package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

// Flat rather than a per-kind polymorphic body, deliberately. The detail fields differ by
// kind but there are only a handful of them, and a flat record keeps the wire format
// readable and every field's constraint visible in one place. Which fields are permitted
// for a given `kind` is a cross-field rule, so SubjectService enforces it and rejects a
// mismatch (e.g. `habitat` on an ISLAND) with SubjectKindMismatchException -> 400, rather
// than silently dropping the value.
//
// Geometries arrive as WKT — "POINT(52.1 26.4)", "POLYGON((...))" — not GeoJSON. WKT is what
// JTS's WKTReader consumes natively, so accepting it costs no extra dependency and no
// hand-rolled parser; a string that doesn't parse, or parses to the wrong geometry type for
// the field, becomes InvalidGeometryException -> 400. Coordinates are lon/lat in WGS84
// (SRID 4326), matching the column types in V15.
//
// `kind` is bound as the enum: a missing value is @NotNull -> 400 with a per-field error,
// and an unrecognised one fails Jackson binding -> 400 HTTP_MESSAGE_NOT_READABLE. Both are
// traced 4xx paths. The GET filter takes a raw String instead — see SubjectController.
public record CreateSubjectRequest(
        @NotNull(message = "{validation.subjectKind.required}") SubjectKind kind,
        @PositiveOrZero(message = "{validation.areaKm2.positive}") BigDecimal areaKm2,
        String location,
        String area,
        String habitat) {
}
