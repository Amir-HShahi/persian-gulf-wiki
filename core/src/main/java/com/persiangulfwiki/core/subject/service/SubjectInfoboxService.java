package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.article.service.SubjectArticleService;
import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.entity.AttributeGroup;
import com.persiangulfwiki.core.attribute.entity.AttributeOption;
import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.config.CacheConfig;
import com.persiangulfwiki.core.config.SupportedLocales;
import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.media.service.MediaReadService;
import com.persiangulfwiki.core.media.service.MediaReadService.SubjectImage;
import com.persiangulfwiki.core.subject.dto.InfoboxGroupResponse;
import com.persiangulfwiki.core.subject.dto.InfoboxOptionResponse;
import com.persiangulfwiki.core.subject.dto.InfoboxRevisionResponse;
import com.persiangulfwiki.core.subject.dto.InfoboxRowResponse;
import com.persiangulfwiki.core.subject.dto.InfoboxSubjectResponse;
import com.persiangulfwiki.core.subject.dto.InfoboxValueResponse;
import com.persiangulfwiki.core.subject.dto.LocalizedTextResponse;
import com.persiangulfwiki.core.subject.dto.SubjectInfoboxResponse;
import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadResponse;
import com.persiangulfwiki.core.subject.entity.FactRank;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectFact;
import com.persiangulfwiki.core.subject.entity.SubjectLiveRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevision;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.exception.SubjectRevisionNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectFactRepository;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

// A subject's infobox: the live revision's PREFERRED facts of its kind's infobox fields, grouped
// and labelled in the reader's language, plus the derived rows computed here. Read from the read
// model, which only approval writes, so nothing pending or rejected shows -- except the main
// image, which only the live revision's payload records.
//
// Cached per (subject, language); SubjectInfoboxCacheService lists what clears it.
@Service
@RequiredArgsConstructor
public class SubjectInfoboxService {

    private static final Comparator<AttributeGroup> GROUP_ORDER =
            Comparator.comparingInt(AttributeGroup::getSort).thenComparing(AttributeGroup::getKey);
    private static final Comparator<AttributeDefinition> DEFINITION_ORDER =
            Comparator.comparingInt(AttributeDefinition::getSort).thenComparing(AttributeDefinition::getKey);
    // Persons per km², to two decimals; the client formats the digits.
    private static final int DENSITY_SCALE = 2;
    private static final String FARSI = SupportedLocales.FARSI.getLanguage();

    private final SubjectRepository subjectRepository;
    private final SubjectLiveRevisionRepository subjectLiveRevisionRepository;
    private final SubjectRevisionRepository subjectRevisionRepository;
    private final SubjectFactRepository subjectFactRepository;
    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeGroupRepository attributeGroupRepository;
    private final AttributeOptionRepository attributeOptionRepository;
    private final LabelService labelService;
    private final SubjectArticleService subjectArticleService;
    private final MediaReadService mediaReadService;
    private final ExpertVerificationService expertVerificationService;
    private final ObjectMapper objectMapper;

    /** {@code language} must be one of the supported languages; the cache key relies on it. */
    @Cacheable(cacheNames = CacheConfig.SUBJECT_INFOBOX, key = "#subjectId + ':' + #language")
    @Transactional(readOnly = true)
    public SubjectInfoboxResponse get(UUID subjectId, String language) {
        Subject subject = subjectRepository.findByIdAndDeletedAtIsNull(subjectId)
                .orElseThrow(SubjectNotFoundException::new);
        LocalizedLabelResponse name = labelService.resolveSubjects(List.of(subjectId), language).get(subjectId);
        SubjectLiveRevision live = subjectLiveRevisionRepository.findById(subjectId).orElse(null);
        if (live == null) {
            // Never approved (only a dev fixture subject is): there are no facts to show.
            return new SubjectInfoboxResponse(subjectId, subject.getKind(), name, null, List.of(), null);
        }
        SubjectRevision revision = subjectRevisionRepository.findById(live.getRevisionId())
                .orElseThrow(SubjectRevisionNotFoundException::new);

        return new SubjectInfoboxResponse(subjectId, subject.getKind(), name, mainImage(subjectId, revision),
                groups(subject, language),
                new InfoboxRevisionResponse(revision.getRevisionNumber(),
                        expertVerificationService.verificationOf(subjectId, revision.getRevisionNumber())));
    }

    // Dropped once the image is no longer public, rather than handing out an id that 404s.
    private @Nullable SubjectMainImageResponse mainImage(UUID subjectId, SubjectRevision revision) {
        UUID mediaId = objectMapper.readValue(revision.getPayload(), SubjectPayloadResponse.class).mainImageMediaId();
        if (mediaId == null) {
            return null;
        }
        SubjectImage image = mediaReadService.publishedImagesOfSubjects(Map.of(subjectId, mediaId)).get(subjectId);
        return image != null
                ? new SubjectMainImageResponse(image.mediaId(), image.placeholder(), image.variants())
                : null;
    }

    private List<InfoboxGroupResponse> groups(Subject subject, String language) {
        // Every field of the kind, not only infobox ones: a derived row may read a field that is
        // itself kept out of the infobox.
        List<AttributeDefinition> definitions = attributeDefinitionRepository.findByKind(subject.getKind());
        Map<UUID, String> keysById = definitions.stream()
                .collect(Collectors.toMap(AttributeDefinition::getId, AttributeDefinition::getKey));
        List<SubjectFact> preferred = subjectFactRepository
                .findBySubjectIdAndRankOrderBySortAsc(subject.getId(), FactRank.PREFERRED);
        // A reference to a deleted subject is hidden like the subject itself; it shows again on a restore.
        Set<UUID> referencedIds = preferred.stream().map(SubjectFact::getValueRefSubjectId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> deletedReferences = referencedIds.isEmpty() ? Set.of()
                : subjectRepository.findDeletedIds(referencedIds);
        Map<String, List<SubjectFact>> factsByKey = preferred.stream()
                .filter(fact -> keysById.containsKey(fact.getAttributeId()))
                .filter(fact -> fact.getValueRefSubjectId() == null
                        || !deletedReferences.contains(fact.getValueRefSubjectId()))
                .collect(Collectors.groupingBy(fact -> keysById.get(fact.getAttributeId()), LinkedHashMap::new,
                        Collectors.toList()));
        Lookups lookups = lookups(factsByKey.values().stream().flatMap(List::stream).toList(), language);

        Map<AttributeDefinition, List<InfoboxValueResponse>> valuesByDefinition = new LinkedHashMap<>();
        definitions.stream()
                .filter(AttributeDefinition::isInfobox)
                .sorted(DEFINITION_ORDER)
                .forEach(definition -> {
                    List<InfoboxValueResponse> values = definition.isDerived()
                            ? derivedValues(definition, subject.getId(), factsByKey)
                            : factsByKey.getOrDefault(definition.getKey(), List.of()).stream()
                                    .map(fact -> toValue(definition.getValueType(), fact, lookups, language))
                                    .toList();
                    if (!values.isEmpty()) {
                        valuesByDefinition.put(definition, values);
                    }
                });
        if (valuesByDefinition.isEmpty()) {
            return List.of();
        }

        List<UUID> definitionIds = valuesByDefinition.keySet().stream().map(AttributeDefinition::getId).toList();
        List<UUID> groupIds = valuesByDefinition.keySet().stream().map(AttributeDefinition::getGroupId).distinct()
                .toList();
        Map<UUID, LocalizedLabelResponse> definitionLabels = labelService.resolveAttributes(definitionIds, language);
        Map<UUID, LocalizedLabelResponse> groupLabels = labelService.resolveGroups(groupIds, language);
        Map<UUID, List<InfoboxRowResponse>> rowsByGroup = new LinkedHashMap<>();
        valuesByDefinition.forEach((definition, values) -> rowsByGroup
                .computeIfAbsent(definition.getGroupId(), _ -> new ArrayList<>())
                .add(new InfoboxRowResponse(definition.getKey(), definitionLabels.get(definition.getId()),
                        definition.getValueType(), definition.getUnit(), definition.isDerived(), values)));

        return attributeGroupRepository.findAllById(groupIds).stream()
                .sorted(GROUP_ORDER)
                .map(group -> new InfoboxGroupResponse(group.getKey(), groupLabels.get(group.getId()),
                        List.copyOf(rowsByGroup.get(group.getId()))))
                .toList();
    }

    private List<InfoboxValueResponse> derivedValues(AttributeDefinition definition, UUID subjectId,
            Map<String, List<SubjectFact>> factsByKey) {
        return switch (definition.getKey()) {
            case DerivedAttributes.POPULATION_DENSITY -> definition.getValueType() == AttributeValueType.NUMBER
                    ? populationDensity(factsByKey)
                    : List.of();
            case DerivedAttributes.HAS_360_TOUR -> definition.getValueType() == AttributeValueType.BOOL
                    ? List.of(new InfoboxValueResponse(null, null, null, subjectRepository.hasPublished360Tour(subjectId),
                            null, null, null, null, null))
                    : List.of();
            default -> List.of();
        };
    }

    // Population over area, as of the population figure's date. No row when either is missing or
    // the area is zero.
    private static List<InfoboxValueResponse> populationDensity(Map<String, List<SubjectFact>> factsByKey) {
        SubjectFact population = firstNumber(factsByKey.get(DerivedAttributes.POPULATION));
        SubjectFact area = firstNumber(factsByKey.get(DerivedAttributes.AREA));
        if (population == null || area == null || area.getValueNumber().signum() <= 0) {
            return List.of();
        }
        BigDecimal density = population.getValueNumber().divide(area.getValueNumber(), DENSITY_SCALE,
                RoundingMode.HALF_UP);
        return List.of(new InfoboxValueResponse(density, null, null, null, null, null, null, population.getAsOf(),
                null));
    }

    private static @Nullable SubjectFact firstNumber(@Nullable List<SubjectFact> facts) {
        return facts == null ? null
                : facts.stream().filter(fact -> fact.getValueNumber() != null).findFirst().orElse(null);
    }

    private InfoboxValueResponse toValue(AttributeValueType type, SubjectFact fact, Lookups lookups, String language) {
        return switch (type) {
            case NUMBER -> new InfoboxValueResponse(fact.getValueNumber(), null, null, null, null, null, null,
                    fact.getAsOf(), fact.getSourceId());
            case ENUM -> {
                AttributeOption option = lookups.options().get(fact.getValueOptionId());
                InfoboxOptionResponse optionResponse = option == null ? null
                        : new InfoboxOptionResponse(option.getCode(), lookups.optionLabels().get(option.getId()));
                yield new InfoboxValueResponse(null, optionResponse, null, null, null, null, null, fact.getAsOf(),
                        fact.getSourceId());
            }
            case SUBJECT_REF -> {
                UUID referencedId = Objects.requireNonNull(fact.getValueRefSubjectId());
                yield new InfoboxValueResponse(null, null, new InfoboxSubjectResponse(referencedId,
                        lookups.subjectNames().get(referencedId), lookups.subjectSlugs().get(referencedId)), null,
                        null, null, null, fact.getAsOf(), fact.getSourceId());
            }
            case BOOL -> new InfoboxValueResponse(null, null, null, fact.getValueBool(), null, null, null,
                    fact.getAsOf(), fact.getSourceId());
            case DATE -> new InfoboxValueResponse(null, null, null, null, fact.getValueDate(), null, null,
                    fact.getAsOf(), fact.getSourceId());
            case PLAIN_TEXT -> new InfoboxValueResponse(null, null, null, null, null, fact.getValueText(), null,
                    fact.getAsOf(), fact.getSourceId());
            case LOCALIZED_TEXT -> new InfoboxValueResponse(null, null, null, null, null, null,
                    localize(fact.getValueLocalized(), language), fact.getAsOf(), fact.getSourceId());
        };
    }

    // Same fallback as labels: the reader's language, else Farsi marked as a fallback. Farsi is
    // required on every LOCALIZED_TEXT value, so this is only null for a value with no JSON at all.
    private @Nullable LocalizedLabelResponse localize(@Nullable String json, String language) {
        if (json == null) {
            return null;
        }
        LocalizedTextResponse text = objectMapper.readValue(json, LocalizedTextResponse.class);
        String inLanguage = switch (language) {
            case "en" -> text.en();
            case "ar" -> text.ar();
            default -> text.fa();
        };
        if (inLanguage != null) {
            return new LocalizedLabelResponse(inLanguage, language, false);
        }
        return text.fa() == null ? null : new LocalizedLabelResponse(text.fa(), FARSI, true);
    }

    // Everything the facts point at, fetched once per infobox rather than once per value.
    private Lookups lookups(List<SubjectFact> facts, String language) {
        List<UUID> optionIds = facts.stream().map(SubjectFact::getValueOptionId).filter(Objects::nonNull).distinct()
                .toList();
        List<UUID> referencedIds = facts.stream().map(SubjectFact::getValueRefSubjectId).filter(Objects::nonNull)
                .distinct().toList();
        Map<UUID, AttributeOption> options = optionIds.isEmpty() ? Map.of()
                : attributeOptionRepository.findAllById(optionIds).stream()
                        .collect(Collectors.toMap(AttributeOption::getId, Function.identity()));
        return new Lookups(options, labelService.resolveOptions(optionIds, language),
                labelService.resolveSubjects(referencedIds, language),
                subjectArticleService.mainArticleSlugs(referencedIds, language));
    }

    private record Lookups(
            Map<UUID, AttributeOption> options,
            Map<UUID, LocalizedLabelResponse> optionLabels,
            Map<UUID, LocalizedLabelResponse> subjectNames,
            Map<UUID, String> subjectSlugs) {
    }
}
