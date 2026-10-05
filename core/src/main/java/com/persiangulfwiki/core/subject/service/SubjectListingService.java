package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.article.service.SubjectArticleService;
import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.entity.AttributeGroup;
import com.persiangulfwiki.core.attribute.entity.AttributeOption;
import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.media.service.MediaReadService;
import com.persiangulfwiki.core.subject.dto.SubjectFacetOptionResponse;
import com.persiangulfwiki.core.subject.dto.SubjectFacetResponse;
import com.persiangulfwiki.core.subject.dto.SubjectListItemResponse;
import com.persiangulfwiki.core.subject.dto.SubjectListResponse;
import com.persiangulfwiki.core.subject.dto.SubjectReferencesResponse;
import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectFilterException;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectFilterException.Violation;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.Condition;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.Has360TourCondition;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.OptionCondition;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.ReferenceCondition;
import com.persiangulfwiki.core.subject.repository.SubjectFacetFilter.TrueCondition;
import com.persiangulfwiki.core.subject.repository.SubjectFactRepository;
import com.persiangulfwiki.core.subject.repository.SubjectFactRepository.Badge;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository.MainImage;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// GET /api/subjects: one page of subjects matching the filters, and the option counts the filter
// panel is built from. Disjunctive facets (decision 12): OR within a field, AND across fields, and
// each field's counts computed with every other field's selection applied but not its own, so a
// count says how many subjects that option matches given the rest of the selection.
//
// Reads only the read model, which only approval writes. No facet cache: about one grouped query
// per facet, a handful per request.
//
// Also GET /api/subjects/{subjectId}/referenced-by, the other listing of subject cards: the subjects
// whose PREFERRED value of one SUBJECT_REF field points at a subject (a province's islands).
@Service
@RequiredArgsConstructor
public class SubjectListingService {

    private static final String ATTRIBUTE_PREFIX = "attr.";
    private static final String KIND_KEY = "kind";
    private static final String REFERENCE_KEY = "attribute";
    // The one option of a BOOL facet: "has the property". There is no "false" checkbox.
    private static final String TRUE_CODE = "true";
    private static final String KEY_PREFIX = "validation.subjectFilter.";

    private final SubjectRepository subjectRepository;
    private final SubjectService subjectService;
    private final SubjectFactRepository subjectFactRepository;
    private final SubjectLiveRevisionRepository subjectLiveRevisionRepository;
    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeGroupRepository attributeGroupRepository;
    private final AttributeOptionRepository attributeOptionRepository;
    private final LabelService labelService;
    private final SubjectArticleService subjectArticleService;
    private final MediaReadService mediaReadService;

    /**
     * Only the {@code attr.*} entries of {@code parameters} are read. An unknown kind throws
     * InvalidSubjectKindException; every problem with the {@code attr.*} filters is collected into
     * one InvalidSubjectFilterException. {@code language} must be one of the supported languages.
     */
    @Transactional(readOnly = true)
    public SubjectListResponse list(@Nullable List<String> kindParameters, MultiValueMap<String, String> parameters,
            String language, Pageable pageable) {
        Set<SubjectKind> kinds = parseKinds(kindParameters);
        List<AttributeDefinition> definitions = kinds.isEmpty() ? List.of()
                : attributeDefinitionRepository.findByKindIn(kinds);
        Map<UUID, List<AttributeOption>> optionsByDefinition = optionsOf(definitions);
        SubjectFacetFilter filter = new SubjectFacetFilter(kinds,
                conditions(kinds, definitions, optionsByDefinition, parameters));

        List<UUID> ids = subjectRepository.findMatchingIds(filter, pageable.getOffset(), pageable.getPageSize());
        return new SubjectListResponse(items(ids, language), subjectRepository.countMatching(filter),
                facets(filter, definitions, optionsByDefinition, language));
    }

    /**
     * One page of the subjects whose PREFERRED value of the SUBJECT_REF field {@code attributeKey}
     * points at {@code subjectId}, optionally of one kind. An attribute that is unknown, not a
     * SUBJECT_REF, or refers to another kind than the subject's throws InvalidSubjectFilterException.
     */
    @Transactional(readOnly = true)
    public SubjectReferencesResponse referencedBy(UUID subjectId, String attributeKey, @Nullable String kindFilter,
            String language, Pageable pageable) {
        Subject subject = subjectRepository.findByIdAndDeletedAtIsNull(subjectId)
                .orElseThrow(SubjectNotFoundException::new);
        SubjectKind kind = kindFilter == null ? null : SubjectService.parseKind(kindFilter);
        AttributeDefinition definition = attributeDefinitionRepository.findByKey(attributeKey)
                .orElseThrow(() -> new InvalidSubjectFilterException(
                        List.of(violation(REFERENCE_KEY, "attributeUnknown", attributeKey))));
        if (definition.getValueType() != AttributeValueType.SUBJECT_REF) {
            throw new InvalidSubjectFilterException(
                    List.of(violation(REFERENCE_KEY, "attributeNotReference", attributeKey)));
        }
        if (definition.getRefKind() != subject.getKind()) {
            throw new InvalidSubjectFilterException(List.of(violation(REFERENCE_KEY, "attributeRefKindMismatch",
                    attributeKey, String.valueOf(definition.getRefKind()))));
        }

        Page<UUID> page = subjectRepository.findIdsReferencing(subjectId, definition.getId(), kind, pageable);
        return new SubjectReferencesResponse(items(page.getContent(), language), page.getTotalElements());
    }

    // Both `kind=ISLAND,PORT` and `kind=ISLAND&kind=PORT`; no kind means every kind.
    private static Set<SubjectKind> parseKinds(@Nullable List<String> kindParameters) {
        Set<SubjectKind> kinds = EnumSet.noneOf(SubjectKind.class);
        if (kindParameters != null) {
            splitValues(kindParameters).forEach(kind -> kinds.add(SubjectService.parseKind(kind)));
        }
        return kinds;
    }

    private List<Condition> conditions(Set<SubjectKind> kinds, List<AttributeDefinition> definitions,
            Map<UUID, List<AttributeOption>> optionsByDefinition, MultiValueMap<String, String> parameters) {
        // Sorted so the errors come back in a stable order.
        Map<String, Set<String>> selections = new TreeMap<>();
        parameters.forEach((parameter, values) -> {
            Set<String> selected = splitValues(values);
            // An empty selection is no selection (decision 13), not an error.
            if (parameter.startsWith(ATTRIBUTE_PREFIX) && !selected.isEmpty()) {
                selections.put(parameter, selected);
            }
        });
        if (selections.isEmpty()) {
            return List.of();
        }
        // Fields are scoped by kind, so without one there is nothing to resolve a key against.
        if (kinds.isEmpty()) {
            throw new InvalidSubjectFilterException(List.of(violation(KIND_KEY, "kindRequired")));
        }

        Map<String, AttributeDefinition> definitionsByKey = definitions.stream()
                .collect(Collectors.toMap(AttributeDefinition::getKey, Function.identity()));
        List<Violation> violations = new ArrayList<>();
        List<Condition> conditions = new ArrayList<>();
        selections.forEach((parameter, values) -> {
            String key = parameter.substring(ATTRIBUTE_PREFIX.length());
            AttributeDefinition definition = definitionsByKey.get(key);
            if (definition == null) {
                violations.add(violation(parameter,
                        attributeDefinitionRepository.existsByKey(key) ? "attributeNotForKind" : "attributeUnknown", key));
            } else if (!isListable(definition)) {
                violations.add(violation(parameter, "attributeNotFilterable", key));
            } else {
                int before = violations.size();
                Condition condition = switch (definition.getValueType()) {
                    case ENUM -> optionCondition(definition, parameter, values,
                            optionsByDefinition.getOrDefault(definition.getId(), List.of()), violations);
                    case SUBJECT_REF -> referenceCondition(definition, parameter, values, violations);
                    default -> trueCondition(definition, parameter, values, violations);
                };
                if (violations.size() == before) {
                    conditions.add(condition);
                }
            }
        });
        if (!violations.isEmpty()) {
            throw new InvalidSubjectFilterException(violations);
        }
        return conditions;
    }

    // Codes are upper case (V22's format CHECK), so matching them case-insensitively is safe and
    // matches how `kind` is read.
    private static Condition optionCondition(AttributeDefinition definition, String parameter, Set<String> values,
            List<AttributeOption> options, List<Violation> violations) {
        Map<String, UUID> idsByCode = options.stream()
                .collect(Collectors.toMap(AttributeOption::getCode, AttributeOption::getId));
        Set<UUID> optionIds = new LinkedHashSet<>();
        for (String value : values) {
            UUID optionId = idsByCode.get(value.toUpperCase(Locale.ROOT));
            if (optionId == null) {
                violations.add(violation(parameter, "optionUnknown", value));
            } else {
                optionIds.add(optionId);
            }
        }
        return new OptionCondition(definition.getKey(), definition.getId(), optionIds);
    }

    // A merged-away subject's id (from a bookmarked filter URL, say) selects the subject it was merged
    // into; any other deleted subject is unknown, as it is in the facet's options.
    private Condition referenceCondition(AttributeDefinition definition, String parameter, Set<String> values,
            List<Violation> violations) {
        Map<String, UUID> idsByValue = new LinkedHashMap<>();
        for (String value : values) {
            try {
                idsByValue.put(value, UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                violations.add(violation(parameter, "subjectUnknown", value));
            }
        }
        Map<UUID, UUID> redirects = subjectService.redirectTargets(idsByValue.values());
        idsByValue.replaceAll((_, id) -> redirects.getOrDefault(id, id));
        Set<UUID> ofRefKind = subjectRepository.findAllById(idsByValue.values()).stream()
                .filter(subject -> subject.getKind() == definition.getRefKind() && !subject.isDeleted())
                .map(Subject::getId)
                .collect(Collectors.toSet());
        idsByValue.forEach((value, id) -> {
            if (!ofRefKind.contains(id)) {
                violations.add(violation(parameter, "subjectUnknown", value));
            }
        });
        return new ReferenceCondition(definition.getKey(), definition.getId(), ofRefKind);
    }

    private static Condition trueCondition(AttributeDefinition definition, String parameter, Set<String> values,
            List<Violation> violations) {
        values.stream()
                .filter(value -> !TRUE_CODE.equalsIgnoreCase(value))
                .forEach(value -> violations.add(violation(parameter, "boolValue", value)));
        return trueConditionFor(definition);
    }

    private static Condition trueConditionFor(AttributeDefinition definition) {
        return definition.isDerived()
                ? new Has360TourCondition(definition.getKey())
                : new TrueCondition(definition.getKey(), definition.getId());
    }

    // A field that has a checkbox facet: filterable, and of a type whose values are a fixed set.
    // Filterable NUMBER and DATE fields wait for range filters (out of scope). The only derived
    // field with something computing it as a filter is has_360_tour.
    private static boolean isListable(AttributeDefinition definition) {
        if (!definition.isFilterable()) {
            return false;
        }
        if (definition.isDerived()) {
            return definition.getKey().equals(DerivedAttributes.HAS_360_TOUR)
                    && definition.getValueType() == AttributeValueType.BOOL;
        }
        return switch (definition.getValueType()) {
            case ENUM, SUBJECT_REF, BOOL -> true;
            case NUMBER, DATE, PLAIN_TEXT, LOCALIZED_TEXT -> false;
        };
    }

    private List<SubjectListItemResponse> items(List<UUID> ids, String language) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Subject> subjects = subjectRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Subject::getId, Function.identity()));
        Map<UUID, LocalizedLabelResponse> names = labelService.resolveSubjects(ids, language);
        Map<UUID, String> slugs = subjectArticleService.mainArticleSlugs(ids, language);
        Map<UUID, UUID> images = mediaReadService.retainPublishedImagesOfSubjects(
                subjectLiveRevisionRepository.findMainImages(ids).stream()
                        .collect(Collectors.toMap(MainImage::getSubjectId, MainImage::getMediaId)));
        Map<UUID, Map<String, String>> badges = new HashMap<>();
        for (Badge badge : subjectFactRepository.findBadges(ids)) {
            badges.computeIfAbsent(badge.getSubjectId(), _ -> new LinkedHashMap<>())
                    .put(badge.getAttribute(), badge.getCode());
        }

        return ids.stream()
                .map(id -> new SubjectListItemResponse(id, subjects.get(id).getKind(), names.get(id), slugs.get(id),
                        images.containsKey(id) ? new SubjectMainImageResponse(images.get(id)) : null,
                        Collections.unmodifiableMap(badges.getOrDefault(id, new LinkedHashMap<>()))))
                .toList();
    }

    // The kind facet first, then each listable field of the selected kinds in infobox order. With no
    // kind selected there are no fields to offer, only kinds.
    private List<SubjectFacetResponse> facets(SubjectFacetFilter filter, List<AttributeDefinition> definitions,
            Map<UUID, List<AttributeOption>> optionsByDefinition, String language) {
        List<SubjectFacetResponse> facets = new ArrayList<>();
        facets.add(kindFacet(filter, language));

        List<AttributeDefinition> listable = definitions.stream().filter(SubjectListingService::isListable).toList();
        if (listable.isEmpty()) {
            return List.copyOf(facets);
        }
        Map<UUID, AttributeGroup> groups = attributeGroupRepository
                .findAllById(listable.stream().map(AttributeDefinition::getGroupId).distinct().toList()).stream()
                .collect(Collectors.toMap(AttributeGroup::getId, Function.identity()));
        Map<UUID, LocalizedLabelResponse> labels = labelService.resolveAttributes(
                listable.stream().map(AttributeDefinition::getId).toList(), language);
        Map<UUID, LocalizedLabelResponse> optionLabels = labelService.resolveOptions(
                optionsByDefinition.values().stream().flatMap(List::stream).map(AttributeOption::getId).toList(),
                language);

        listable.stream()
                .sorted(Comparator.comparing((AttributeDefinition definition) -> groups.get(definition.getGroupId()),
                                Comparator.comparingInt(AttributeGroup::getSort).thenComparing(AttributeGroup::getKey))
                        .thenComparingInt(AttributeDefinition::getSort)
                        .thenComparing(AttributeDefinition::getKey))
                .forEach(definition -> {
                    SubjectFacetFilter others = filter.without(definition.getKey());
                    LocalizedLabelResponse label = labels.get(definition.getId());
                    List<SubjectFacetOptionResponse> options = switch (definition.getValueType()) {
                        case ENUM -> optionFacet(definition, others,
                                optionsByDefinition.getOrDefault(definition.getId(), List.of()), optionLabels);
                        case SUBJECT_REF -> referenceFacet(definition, others, language);
                        default -> List.of(new SubjectFacetOptionResponse(TRUE_CODE, label,
                                subjectRepository.countMatching(others.with(trueConditionFor(definition))), false));
                    };
                    facets.add(new SubjectFacetResponse(definition.getKey(), label, options));
                });
        return List.copyOf(facets);
    }

    // Every kind, in declaration order, whichever are selected: the places page shows several.
    private SubjectFacetResponse kindFacet(SubjectFacetFilter filter, String language) {
        List<SubjectKind> allKinds = Arrays.asList(SubjectKind.values());
        Map<SubjectKind, Long> counts = subjectRepository.countByKind(filter.withAnyKind());
        Map<SubjectKind, LocalizedLabelResponse> labels = labelService.resolveKinds(allKinds, language);
        return new SubjectFacetResponse(KIND_KEY, null, allKinds.stream()
                .map(kind -> new SubjectFacetOptionResponse(kind.name(), labels.get(kind),
                        counts.getOrDefault(kind, 0L), false))
                .toList());
    }

    // Every option, retired ones included and flagged, so the panel doesn't shift as counts drop
    // to zero.
    private List<SubjectFacetOptionResponse> optionFacet(AttributeDefinition definition, SubjectFacetFilter others,
            List<AttributeOption> options, Map<UUID, LocalizedLabelResponse> optionLabels) {
        Map<UUID, Long> counts = subjectRepository.countByOption(definition.getId(), others);
        return options.stream()
                .sorted(Comparator.comparingInt(AttributeOption::getSort).thenComparing(AttributeOption::getCode))
                .map(option -> new SubjectFacetOptionResponse(option.getCode(), optionLabels.get(option.getId()),
                        counts.getOrDefault(option.getId(), 0L), option.isDeprecated()))
                .toList();
    }

    // Every undeleted subject of the referenced kind (every province, say), by name in the reader's
    // language.
    private List<SubjectFacetOptionResponse> referenceFacet(AttributeDefinition definition, SubjectFacetFilter others,
            String language) {
        SubjectKind refKind = definition.getRefKind();
        List<UUID> candidates = refKind == null ? List.of()
                : subjectRepository.findByKindAndDeletedAtIsNull(refKind).stream().map(Subject::getId).toList();
        Map<UUID, Long> counts = subjectRepository.countByReferencedSubject(definition.getId(), others);
        Map<UUID, LocalizedLabelResponse> names = labelService.resolveSubjects(candidates, language);
        Collator collator = Collator.getInstance(Locale.forLanguageTag(language));
        return candidates.stream()
                .map(id -> new SubjectFacetOptionResponse(id.toString(), names.get(id), counts.getOrDefault(id, 0L),
                        false))
                .sorted(Comparator.comparing((SubjectFacetOptionResponse option) ->
                                option.label() == null ? null : option.label().value(), Comparator.nullsLast(collator))
                        .thenComparing(SubjectFacetOptionResponse::code))
                .toList();
    }

    private Map<UUID, List<AttributeOption>> optionsOf(List<AttributeDefinition> definitions) {
        List<UUID> enumIds = definitions.stream()
                .filter(definition -> definition.getValueType() == AttributeValueType.ENUM)
                .map(AttributeDefinition::getId)
                .toList();
        return enumIds.isEmpty() ? Map.of()
                : attributeOptionRepository.findByAttributeIdIn(enumIds).stream()
                        .collect(Collectors.groupingBy(AttributeOption::getAttributeId));
    }

    // Comma-separated and/or repeated; blanks dropped, duplicates collapsed, order kept.
    private static Set<String> splitValues(List<String> values) {
        Set<String> split = new LinkedHashSet<>();
        values.stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(split::add);
        return split;
    }

    private static Violation violation(String parameter, String key, String... args) {
        return new Violation(parameter, KEY_PREFIX + key, List.of(args));
    }
}
