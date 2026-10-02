package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.label.dto.LabelValuesResponse;
import com.persiangulfwiki.core.label.service.LabelReferent;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.subject.dto.LocalizedTextRequest;
import com.persiangulfwiki.core.subject.dto.SubjectAliasesRequest;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.dto.SubjectResponse;
import com.persiangulfwiki.core.subject.entity.FishingGround;
import com.persiangulfwiki.core.subject.entity.Island;
import com.persiangulfwiki.core.subject.entity.OilField;
import com.persiangulfwiki.core.subject.entity.Port;
import com.persiangulfwiki.core.subject.entity.Province;
import com.persiangulfwiki.core.subject.entity.Species;
import com.persiangulfwiki.core.subject.entity.Strait;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectAlias;
import com.persiangulfwiki.core.subject.entity.SubjectFact;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectLiveRevision;
import com.persiangulfwiki.core.subject.repository.FishingGroundRepository;
import com.persiangulfwiki.core.subject.repository.IslandRepository;
import com.persiangulfwiki.core.subject.repository.OilFieldRepository;
import com.persiangulfwiki.core.subject.repository.PortRepository;
import com.persiangulfwiki.core.subject.repository.ProvinceRepository;
import com.persiangulfwiki.core.subject.repository.SpeciesRepository;
import com.persiangulfwiki.core.subject.repository.StraitRepository;
import com.persiangulfwiki.core.subject.repository.SubjectAliasRepository;
import com.persiangulfwiki.core.subject.repository.SubjectFactRepository;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tools.jackson.databind.ObjectMapper;

// Writes the read model of a subject's live revision -- SUBJECT labels, subject_aliases,
// subject_facts, the subtype geometry and the live pointer -- and reads it back as a payload for
// a subject that has no approved revision to copy. Called only from SubjectRevisionService inside
// its approval transaction, which already holds the subject row lock, so nothing here opens a
// transaction of its own.
@Service
@RequiredArgsConstructor
public class SubjectReadModelService {

    private final SubjectRepository subjectRepository;
    private final IslandRepository islandRepository;
    private final PortRepository portRepository;
    private final OilFieldRepository oilFieldRepository;
    private final SpeciesRepository speciesRepository;
    private final StraitRepository straitRepository;
    private final FishingGroundRepository fishingGroundRepository;
    private final ProvinceRepository provinceRepository;
    private final SubjectFactRepository subjectFactRepository;
    private final SubjectAliasRepository subjectAliasRepository;
    private final SubjectLiveRevisionRepository subjectLiveRevisionRepository;
    private final SubjectService subjectService;
    private final LabelService labelService;
    private final ObjectMapper objectMapper;

    // For an approved proposal. The dev marker is carried over from the revision so that a fixture
    // proposal approved through the real flow yields a fixture subject the sweepers can reclaim;
    // on every real revision it is null.
    Subject createSubject(SubjectKind kind, @Nullable String devMarker) {
        Subject subject = subjectRepository.save(Subject.builder().kind(kind).devMarker(devMarker).build());
        switch (kind) {
            case ISLAND -> islandRepository.save(Island.builder().subject(subject).build());
            case PORT -> portRepository.save(Port.builder().subject(subject).build());
            case OIL_FIELD -> oilFieldRepository.save(OilField.builder().subject(subject).build());
            case SPECIES -> speciesRepository.save(Species.builder().subject(subject).build());
            case STRAIT -> straitRepository.save(Strait.builder().subject(subject).build());
            case FISHING_GROUND -> fishingGroundRepository.save(FishingGround.builder().subject(subject).build());
            case PROVINCE -> provinceRepository.save(Province.builder().subject(subject).build());
        }
        return subject;
    }

    // Replaces everything the read model holds for the subject with the validated payload, then
    // points the subject at the revision. The infobox cache this should also evict arrives with
    // the infobox itself (phase 5).
    void publish(Subject subject, UUID revisionId, ValidatedSubjectPayload validated) {
        UUID subjectId = subject.getId();
        SubjectPayloadRequest payload = validated.payload();

        labelService.replaceSubjectNames(subjectId, payload.names().fa(), payload.names().en(),
                payload.names().ar());

        subjectAliasRepository.deleteBySubjectId(subjectId);
        SubjectAliasesRequest aliases = payload.aliases();
        subjectAliasRepository.saveAll(Stream.of(
                        aliasRows(subjectId, "fa", aliases.fa()),
                        aliasRows(subjectId, "en", aliases.en()),
                        aliasRows(subjectId, "ar", aliases.ar()))
                .flatMap(List::stream)
                .toList());

        subjectFactRepository.deleteBySubjectId(subjectId);
        subjectFactRepository.saveAll(validated.facts().stream()
                .map(fact -> SubjectFact.builder()
                        .subjectId(subjectId)
                        .attributeId(fact.attributeId())
                        .revisionId(revisionId)
                        .sort(fact.sort())
                        .valueNumber(fact.number())
                        .valueOptionId(fact.optionId())
                        .valueRefSubjectId(fact.refSubjectId())
                        .valueBool(fact.bool())
                        .valueDate(fact.date())
                        .valueText(fact.text())
                        .valueLocalized(fact.localized() == null ? null
                                : objectMapper.writeValueAsString(fact.localized()))
                        .asOf(fact.asOf())
                        .rank(fact.rank())
                        .sourceId(fact.sourceId())
                        .build())
                .toList());

        writeGeometry(subject, validated.geometry());

        subjectLiveRevisionRepository.save(SubjectLiveRevision.builder()
                .subjectId(subjectId)
                .revisionId(revisionId)
                .approvedAt(Instant.now())
                .build());
    }

    // What the read model currently says, in payload form: the starting point for editing a
    // subject that has never had a revision approved (minted by the dev fixture route, or created
    // before revisions existed). It has no facts, since only approval writes them.
    SubjectPayloadRequest snapshot(Subject subject) {
        LabelValuesResponse names = labelService.values(LabelReferent.subject(subject.getId()));
        Map<String, List<String>> aliases = subjectAliasRepository.findBySubjectId(subject.getId()).stream()
                .collect(Collectors.groupingBy(SubjectAlias::getLanguage,
                        Collectors.mapping(SubjectAlias::getValue, Collectors.toList())));
        SubjectResponse detail = subjectService.get(subject.getId());
        String geometry = Stream.of(detail.location(), detail.area(), detail.habitat(), detail.boundary())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new SubjectPayloadRequest(new LocalizedTextRequest(names.fa(), names.en(), names.ar()),
                new SubjectAliasesRequest(aliases.getOrDefault("fa", List.of()), aliases.getOrDefault("en", List.of()),
                        aliases.getOrDefault("ar", List.of())),
                List.of(), geometry, null);
    }

    // The subtype row is created if missing: a subject minted by the dev fixture route may have
    // none. Each cast is safe because the validator already typed the geometry for the kind.
    private void writeGeometry(Subject subject, @Nullable Geometry geometry) {
        UUID subjectId = subject.getId();
        switch (subject.getKind()) {
            case ISLAND -> {
                Island island = islandRepository.findById(subjectId)
                        .orElseGet(() -> Island.builder().subject(subject).build());
                island.setLocation((Point) geometry);
                islandRepository.save(island);
            }
            case PORT -> {
                Port port = portRepository.findById(subjectId).orElseGet(() -> Port.builder().subject(subject).build());
                port.setLocation((Point) geometry);
                portRepository.save(port);
            }
            case OIL_FIELD -> {
                OilField oilField = oilFieldRepository.findById(subjectId)
                        .orElseGet(() -> OilField.builder().subject(subject).build());
                oilField.setArea((Polygon) geometry);
                oilFieldRepository.save(oilField);
            }
            case SPECIES -> {
                Species species = speciesRepository.findById(subjectId)
                        .orElseGet(() -> Species.builder().subject(subject).build());
                species.setHabitat((Polygon) geometry);
                speciesRepository.save(species);
            }
            case STRAIT -> {
                Strait strait = straitRepository.findById(subjectId)
                        .orElseGet(() -> Strait.builder().subject(subject).build());
                strait.setArea((Polygon) geometry);
                straitRepository.save(strait);
            }
            case FISHING_GROUND -> {
                FishingGround fishingGround = fishingGroundRepository.findById(subjectId)
                        .orElseGet(() -> FishingGround.builder().subject(subject).build());
                fishingGround.setArea((Polygon) geometry);
                fishingGroundRepository.save(fishingGround);
            }
            case PROVINCE -> {
                Province province = provinceRepository.findById(subjectId)
                        .orElseGet(() -> Province.builder().subject(subject).build());
                province.setBoundary((MultiPolygon) geometry);
                provinceRepository.save(province);
            }
        }
    }

    private static List<SubjectAlias> aliasRows(UUID subjectId, String language, List<String> values) {
        return values.stream()
                .map(value -> SubjectAlias.builder().subjectId(subjectId).language(language).value(value).build())
                .toList();
    }
}
