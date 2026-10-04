package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.SubjectResponse;
import com.persiangulfwiki.core.subject.entity.Island;
import com.persiangulfwiki.core.subject.entity.OilField;
import com.persiangulfwiki.core.subject.entity.Port;
import com.persiangulfwiki.core.subject.entity.Species;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectKindException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.FishingGroundRepository;
import com.persiangulfwiki.core.subject.repository.IslandRepository;
import com.persiangulfwiki.core.subject.repository.OilFieldRepository;
import com.persiangulfwiki.core.subject.repository.PortRepository;
import com.persiangulfwiki.core.subject.repository.ProvinceRepository;
import com.persiangulfwiki.core.subject.repository.SpeciesRepository;
import com.persiangulfwiki.core.subject.repository.StraitRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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

    @Transactional(readOnly = true)
    public SubjectResponse get(UUID subjectId) {
        return toResponse(subjectRepository.findById(subjectId).orElseThrow(SubjectNotFoundException::new));
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

    private SubjectResponse toResponse(Subject subject) {
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
                location, area, habitat, boundary);
    }

    private String toWkt(Geometry geometry) {
        return geometry == null ? null : new WKTWriter().write(geometry);
    }
}
