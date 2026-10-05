package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.dto.SubjectResponse;
import com.persiangulfwiki.core.subject.entity.Island;
import com.persiangulfwiki.core.subject.entity.OilField;
import com.persiangulfwiki.core.subject.entity.Port;
import com.persiangulfwiki.core.subject.entity.Species;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectRedirect;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectKindException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.FishingGroundRepository;
import com.persiangulfwiki.core.subject.repository.IslandRepository;
import com.persiangulfwiki.core.subject.repository.OilFieldRepository;
import com.persiangulfwiki.core.subject.repository.PortRepository;
import com.persiangulfwiki.core.subject.repository.ProvinceRepository;
import com.persiangulfwiki.core.subject.repository.SpeciesRepository;
import com.persiangulfwiki.core.subject.repository.StraitRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRedirectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubjectService {

    private final SubjectRepository subjectRepository;
    private final IslandRepository islandRepository;
    private final PortRepository portRepository;
    private final OilFieldRepository oilFieldRepository;
    private final SpeciesRepository speciesRepository;
    private final StraitRepository straitRepository;
    private final FishingGroundRepository fishingGroundRepository;
    private final ProvinceRepository provinceRepository;
    private final SubjectRedirectRepository subjectRedirectRepository;

    /**
     * A subject merged into another answers with that other subject, {@code redirectedFrom} set to the
     * id asked for. Any other deleted subject is a 404, like one that never existed.
     */
    @Transactional(readOnly = true)
    public SubjectResponse get(UUID subjectId) {
        Subject subject = subjectRepository.findById(subjectId).orElseThrow(SubjectNotFoundException::new);
        if (!subject.isDeleted()) {
            return toResponse(subject, null);
        }
        Subject target = subjectRedirectRepository.findById(subjectId)
                .flatMap(redirect -> subjectRepository.findByIdAndDeletedAtIsNull(redirect.getTargetId()))
                .orElseThrow(SubjectNotFoundException::new);
        return toResponse(target, subjectId);
    }

    // Any subject's detail, deleted or not, for callers that already hold the row.
    SubjectResponse describe(Subject subject) {
        return toResponse(subject, null);
    }

    /** Each id of a merged-away subject mapped to the subject it was merged into; every other id is absent. */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> redirectTargets(Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        return subjectRedirectRepository.findAllById(subjectIds).stream()
                .collect(Collectors.toUnmodifiableMap(SubjectRedirect::getSourceId, SubjectRedirect::getTargetId));
    }

    // The payload with every reference to a merged-away subject rewritten to its target; see
    // SubjectReferenceRemapper. The same instance when nothing needed rewriting.
    SubjectPayloadRequest followRedirects(SubjectPayloadRequest payload, @Nullable UUID selfId) {
        return SubjectReferenceRemapper.remap(payload,
                redirectTargets(SubjectReferenceRemapper.referencedIds(payload)), selfId);
    }

    /**
     * The subject a new article about {@code subjectId} is bound to: that subject, or the one it was
     * merged into. Share-locks the row until the caller's transaction ends, so a concurrent delete or
     * merge either finishes first and is seen here, or waits for the article to be bound.
     *
     * @throws SubjectNotFoundException if there is no such subject, or it is deleted and not merged
     */
    @Transactional
    public Subject lockForBinding(UUID subjectId) {
        Subject subject = subjectRepository.findByIdForShare(subjectId).orElseThrow(SubjectNotFoundException::new);
        if (!subject.isDeleted()) {
            return subject;
        }
        return subjectRedirectRepository.findById(subjectId)
                .flatMap(redirect -> subjectRepository.findByIdForShare(redirect.getTargetId()))
                .filter(target -> !target.isDeleted())
                .orElseThrow(SubjectNotFoundException::new);
    }

    // Accepts the filter as a raw String and parses it here rather than binding SubjectKind
    // directly on a controller parameter: binding the enum gives a generic, case-sensitive
    // Spring conversion failure, where this produces a translated 400 carrying the
    // INVALID_SUBJECT_KIND code the frontend can branch on.
    static SubjectKind parseKind(String kindFilter) {
        try {
            return SubjectKind.valueOf(kindFilter.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidSubjectKindException("invalid subject kind filter: " + kindFilter);
        }
    }

    private SubjectResponse toResponse(Subject subject, @Nullable UUID redirectedFrom) {
        String location = null;
        String area = null;
        String habitat = null;
        String boundary = null;

        // Read back through the subtype repositories rather than through a mapped association
        // on Subject: an @OneToOne back-reference per kind would put one mostly-null
        // association per kind on every Subject and make each read a many-way outer join, when
        // only one of them can ever be populated.
        switch (subject.getKind()) {
            case ISLAND -> {
                Island island = islandRepository.findById(subject.getId()).orElse(null);
                if (island != null) {
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
            case STRAIT -> area = straitRepository.findById(subject.getId())
                    .map(strait -> toWkt(strait.getArea()))
                    .orElse(null);
            case FISHING_GROUND -> area = fishingGroundRepository.findById(subject.getId())
                    .map(fishingGround -> toWkt(fishingGround.getArea()))
                    .orElse(null);
            case PROVINCE -> boundary = provinceRepository.findById(subject.getId())
                    .map(province -> toWkt(province.getBoundary()))
                    .orElse(null);
        }

        return new SubjectResponse(subject.getId(), subject.getKind(), subject.getCreatedAt(),
                location, area, habitat, boundary, redirectedFrom);
    }

    private String toWkt(Geometry geometry) {
        return geometry == null ? null : new WKTWriter().write(geometry);
    }
}
