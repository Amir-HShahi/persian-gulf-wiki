package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.CreateSubjectRequest;
import com.persiangulfwiki.core.subject.dto.SubjectResponse;
import com.persiangulfwiki.core.subject.entity.Island;
import com.persiangulfwiki.core.subject.entity.OilField;
import com.persiangulfwiki.core.subject.entity.Port;
import com.persiangulfwiki.core.subject.entity.Species;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.InvalidGeometryException;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectKindException;
import com.persiangulfwiki.core.subject.exception.SubjectKindMismatchException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.IslandRepository;
import com.persiangulfwiki.core.subject.repository.OilFieldRepository;
import com.persiangulfwiki.core.subject.repository.PortRepository;
import com.persiangulfwiki.core.subject.repository.SpeciesRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubjectService {

    // WGS84. Fixed here rather than taken from the request: V15 declares SRID 4326 in every
    // geometry column's type, and Postgres rejects an insert whose SRID doesn't match rather
    // than reprojecting. JTS gives a freshly-parsed WKT geometry SRID 0, so without stamping
    // it here every single create would fail at the insert.
    private static final int SRID_WGS84 = 4326;

    private final SubjectRepository subjectRepository;
    private final IslandRepository islandRepository;
    private final PortRepository portRepository;
    private final OilFieldRepository oilFieldRepository;
    private final SpeciesRepository speciesRepository;

    // Subject row and subtype row are written together or not at all: a subject whose detail
    // row failed to insert is a subject that can never be given one afterwards, since the
    // subtype tables are keyed by the subject's own id and nothing in the API creates a
    // detail row on its own.
    @Transactional
    public SubjectResponse create(CreateSubjectRequest request) {
        rejectFieldsForeignToKind(request);

        Subject subject = subjectRepository.save(Subject.builder()
                .kind(request.kind())
                .build());

        switch (request.kind()) {
            case ISLAND -> islandRepository.save(Island.builder()
                    .subject(subject)
                    .areaKm2(request.areaKm2())
                    .location(parsePoint(request.location()))
                    .build());
            case PORT -> portRepository.save(Port.builder()
                    .subject(subject)
                    .location(parsePoint(request.location()))
                    .build());
            case OIL_FIELD -> oilFieldRepository.save(OilField.builder()
                    .subject(subject)
                    .area(parsePolygon(request.area()))
                    .build());
            case SPECIES -> speciesRepository.save(Species.builder()
                    .subject(subject)
                    .habitat(parsePolygon(request.habitat()))
                    .build());
        }

        return toResponse(subject);
    }

    @Transactional(readOnly = true)
    public SubjectResponse get(UUID subjectId) {
        return toResponse(subjectRepository.findById(subjectId).orElseThrow(SubjectNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public List<SubjectResponse> list(String kindFilter, Pageable pageable) {
        Page<Subject> subjects = kindFilter == null
                ? subjectRepository.findAll(pageable)
                : subjectRepository.findByKind(parseKind(kindFilter), pageable);

        return subjects.getContent().stream().map(this::toResponse).toList();
    }

    // Accepts the filter as a raw String and parses it here rather than binding SubjectKind
    // directly on the controller parameter: binding the enum gives a generic, case-sensitive
    // Spring conversion failure, where this produces a translated 400 carrying the
    // INVALID_SUBJECT_KIND code the frontend can branch on.
    private SubjectKind parseKind(String kindFilter) {
        try {
            return SubjectKind.valueOf(kindFilter.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidSubjectKindException("invalid subject kind filter: " + kindFilter);
        }
    }

    // A field that belongs to a different kind is rejected rather than ignored. Silently
    // dropping it would tell the caller their polygon was stored when nothing stored it —
    // the failure would only surface much later, as a subject mysteriously missing its
    // geometry, with no record of which request lost it.
    private void rejectFieldsForeignToKind(CreateSubjectRequest request) {
        switch (request.kind()) {
            case ISLAND -> rejectForeign(request.area() != null || request.habitat() != null, request.kind());
            case PORT -> rejectForeign(
                    request.areaKm2() != null || request.area() != null || request.habitat() != null, request.kind());
            case OIL_FIELD -> rejectForeign(
                    request.areaKm2() != null || request.location() != null || request.habitat() != null, request.kind());
            case SPECIES -> rejectForeign(
                    request.areaKm2() != null || request.location() != null || request.area() != null, request.kind());
        }
    }

    private void rejectForeign(boolean foreignFieldPresent, SubjectKind kind) {
        if (foreignFieldPresent) {
            throw new SubjectKindMismatchException("field not accepted for subject kind " + kind);
        }
    }

    private Point parsePoint(String wkt) {
        Geometry geometry = parseGeometry(wkt);
        if (geometry == null) {
            return null;
        }
        if (!(geometry instanceof Point point)) {
            throw new InvalidGeometryException("expected a POINT, got " + geometry.getGeometryType());
        }
        return point;
    }

    private Polygon parsePolygon(String wkt) {
        Geometry geometry = parseGeometry(wkt);
        if (geometry == null) {
            return null;
        }
        if (!(geometry instanceof Polygon polygon)) {
            throw new InvalidGeometryException("expected a POLYGON, got " + geometry.getGeometryType());
        }
        return polygon;
    }

    // Null in, null out — every geometry column in V15 is nullable, so an omitted geometry is
    // a subject whose shape isn't known yet, not a bad request.
    //
    // A new WKTReader per call on purpose: WKTReader holds parse state and is not
    // thread-safe, so a shared instance would corrupt geometries under concurrent requests in
    // a way that surfaces as rare, unreproducible garbage rather than a clean failure.
    private Geometry parseGeometry(String wkt) {
        if (wkt == null || wkt.isBlank()) {
            return null;
        }
        GeometryFactory factory = new GeometryFactory(new PrecisionModel(), SRID_WGS84);
        try {
            return new WKTReader(factory).read(wkt);
        } catch (ParseException | IllegalArgumentException e) {
            // IllegalArgumentException as well as ParseException: JTS throws the former for
            // structurally parseable but invalid WKT, e.g. a POLYGON whose ring doesn't close.
            throw new InvalidGeometryException("could not parse WKT geometry: " + e.getMessage());
        }
    }

    private SubjectResponse toResponse(Subject subject) {
        BigDecimal areaKm2 = null;
        String location = null;
        String area = null;
        String habitat = null;

        // Read back through the subtype repositories rather than through a mapped association
        // on Subject: an @OneToOne back-reference per kind would put four mostly-null
        // associations on every Subject and make each read a four-way outer join, when only
        // one of them can ever be populated.
        switch (subject.getKind()) {
            case ISLAND -> {
                Island island = islandRepository.findById(subject.getId()).orElse(null);
                if (island != null) {
                    areaKm2 = island.getAreaKm2();
                    location = toWkt(island.getLocation());
                }
            }
            case PORT -> {
                Port port = portRepository.findById(subject.getId()).orElse(null);
                if (port != null) {
                    location = toWkt(port.getLocation());
                }
            }
            case OIL_FIELD -> {
                OilField oilField = oilFieldRepository.findById(subject.getId()).orElse(null);
                if (oilField != null) {
                    area = toWkt(oilField.getArea());
                }
            }
            case SPECIES -> {
                Species species = speciesRepository.findById(subject.getId()).orElse(null);
                if (species != null) {
                    habitat = toWkt(species.getHabitat());
                }
            }
        }

        return new SubjectResponse(subject.getId(), subject.getKind(), subject.getCreatedAt(),
                areaKm2, location, area, habitat);
    }

    private String toWkt(Geometry geometry) {
        return geometry == null ? null : new WKTWriter().write(geometry);
    }
}
