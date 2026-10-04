package com.persiangulfwiki.core.attribute.service;

import com.persiangulfwiki.core.attribute.dto.KindAttributeGroupResponse;
import com.persiangulfwiki.core.attribute.dto.KindAttributeOptionResponse;
import com.persiangulfwiki.core.attribute.dto.KindAttributeResponse;
import com.persiangulfwiki.core.attribute.dto.SubjectKindAttributesResponse;
import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.entity.AttributeGroup;
import com.persiangulfwiki.core.attribute.entity.AttributeOption;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectKindException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// The public, read-only view of the field vocabulary: one kind's fields, options and labels in
// the reader's language. A fixed handful of queries regardless of how many fields there are.
@Service
@RequiredArgsConstructor
public class AttributeVocabularyService {

    private static final Comparator<AttributeGroup> GROUP_ORDER =
            Comparator.comparingInt(AttributeGroup::getSort).thenComparing(AttributeGroup::getKey);
    private static final Comparator<AttributeDefinition> DEFINITION_ORDER =
            Comparator.comparingInt(AttributeDefinition::getSort).thenComparing(AttributeDefinition::getKey);
    private static final Comparator<AttributeOption> OPTION_ORDER =
            Comparator.comparingInt(AttributeOption::getSort).thenComparing(AttributeOption::getCode);

    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeGroupRepository attributeGroupRepository;
    private final AttributeOptionRepository attributeOptionRepository;
    private final LabelService labelService;

    /** {@code language} must be one of the supported languages; groups with no field for the kind are left out. */
    @Transactional(readOnly = true)
    public SubjectKindAttributesResponse listForKind(String kindRaw, String language) {
        SubjectKind kind = parseKind(kindRaw);

        List<AttributeDefinition> definitions = attributeDefinitionRepository.findByKind(kind);
        List<UUID> definitionIds = definitions.stream().map(AttributeDefinition::getId).toList();
        List<UUID> groupIds = definitions.stream().map(AttributeDefinition::getGroupId).distinct().toList();
        List<AttributeGroup> groups = attributeGroupRepository.findAllById(groupIds);
        Map<UUID, List<AttributeOption>> optionsByDefinition = definitionIds.isEmpty() ? Map.of()
                : attributeOptionRepository.findByAttributeIdIn(definitionIds).stream()
                        .collect(Collectors.groupingBy(AttributeOption::getAttributeId));
        List<UUID> optionIds = optionsByDefinition.values().stream()
                .flatMap(List::stream)
                .map(AttributeOption::getId)
                .toList();

        Map<UUID, LocalizedLabelResponse> groupLabels = labelService.resolveGroups(groupIds, language);
        Map<UUID, LocalizedLabelResponse> definitionLabels = labelService.resolveAttributes(definitionIds, language);
        Map<UUID, LocalizedLabelResponse> optionLabels = labelService.resolveOptions(optionIds, language);
        Map<UUID, List<AttributeDefinition>> definitionsByGroup = definitions.stream()
                .collect(Collectors.groupingBy(AttributeDefinition::getGroupId));

        List<KindAttributeGroupResponse> groupResponses = groups.stream()
                .sorted(GROUP_ORDER)
                .map(group -> new KindAttributeGroupResponse(group.getKey(), groupLabels.get(group.getId()),
                        group.getSort(), definitionsByGroup.get(group.getId()).stream()
                                .sorted(DEFINITION_ORDER)
                                .map(definition -> toResponse(definition, definitionLabels,
                                        optionsByDefinition.getOrDefault(definition.getId(), List.of()),
                                        optionLabels))
                                .toList()))
                .toList();

        return new SubjectKindAttributesResponse(kind, labelService.resolveKind(kind, language).orElse(null),
                groupResponses);
    }

    private KindAttributeResponse toResponse(AttributeDefinition definition,
            Map<UUID, LocalizedLabelResponse> definitionLabels, List<AttributeOption> options,
            Map<UUID, LocalizedLabelResponse> optionLabels) {
        List<KindAttributeOptionResponse> optionResponses = options.stream()
                .sorted(OPTION_ORDER)
                .map(option -> new KindAttributeOptionResponse(option.getCode(), optionLabels.get(option.getId()),
                        option.getSort(), option.isDeprecated()))
                .toList();
        return new KindAttributeResponse(definition.getKey(), definitionLabels.get(definition.getId()),
                definition.getValueType(), definition.getUnit(), definition.getSort(), definition.isMulti(),
                definition.isRequired(), definition.isInfobox(), definition.isFilterable(), definition.getRefKind(),
                definition.getMinValue(), definition.getMaxValue(), definition.isDeprecated(), definition.isDerived(),
                definition.isBadge(), optionResponses);
    }

    // Raw String parsed here, as SubjectService.parseKind does, so a bad kind is a translated
    // 400 INVALID_SUBJECT_KIND rather than a generic conversion failure.
    private SubjectKind parseKind(String kindRaw) {
        try {
            return SubjectKind.valueOf(kindRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidSubjectKindException("invalid subject kind: " + kindRaw);
        }
    }
}
