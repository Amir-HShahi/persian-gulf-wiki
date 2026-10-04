package com.persiangulfwiki.core.attribute.service;

import com.persiangulfwiki.core.attribute.dto.AttributeDefinitionResponse;
import com.persiangulfwiki.core.attribute.dto.AttributeOptionResponse;
import com.persiangulfwiki.core.attribute.dto.CreateAttributeDefinitionRequest;
import com.persiangulfwiki.core.attribute.dto.CreateAttributeOptionRequest;
import com.persiangulfwiki.core.attribute.dto.UpdateAttributeDefinitionRequest;
import com.persiangulfwiki.core.attribute.dto.UpdateAttributeOptionRequest;
import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.attribute.entity.AttributeOption;
import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.exception.AttributeDefinitionNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeGroupNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeHasFactsException;
import com.persiangulfwiki.core.attribute.exception.AttributeHasOptionsException;
import com.persiangulfwiki.core.attribute.exception.AttributeIsDerivedException;
import com.persiangulfwiki.core.attribute.exception.AttributeOptionNotFoundException;
import com.persiangulfwiki.core.attribute.exception.AttributeOptionsNotSupportedException;
import com.persiangulfwiki.core.attribute.exception.DuplicateAttributeKeyException;
import com.persiangulfwiki.core.attribute.exception.DuplicateAttributeOptionCodeException;
import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeOptionRepository;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.label.service.LabelReferent;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.service.SubjectFactService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// Admin-only writes to field definitions and their ENUM options; every one is audit-logged and
// none is moderated. Codes (a definition's key, an option's code) are never changed or deleted
// here — see V22.
@Service
@RequiredArgsConstructor
public class AttributeDefinitionService {

    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeGroupRepository attributeGroupRepository;
    private final AttributeOptionRepository attributeOptionRepository;
    private final LabelService labelService;
    private final SubjectFactService subjectFactService;
    private final AuditLogService auditLogService;

    @Transactional
    public AttributeDefinitionResponse create(UUID actingAdminId, CreateAttributeDefinitionRequest request) {
        if (attributeDefinitionRepository.existsByKey(request.key())) {
            throw new DuplicateAttributeKeyException("attribute definition key already exists: " + request.key());
        }
        requireGroup(request.groupId());

        AttributeDefinition definition = AttributeDefinition.builder()
                .key(request.key())
                .valueType(request.valueType())
                .unit(request.unit())
                .groupId(request.groupId())
                .sort(Objects.requireNonNullElse(request.sort(), 0))
                .isMulti(Boolean.TRUE.equals(request.isMulti()))
                .isRequired(Boolean.TRUE.equals(request.isRequired()))
                .isInfobox(Boolean.TRUE.equals(request.isInfobox()))
                .isFilterable(Boolean.TRUE.equals(request.isFilterable()))
                .isBadge(Boolean.TRUE.equals(request.isBadge()))
                .refKind(request.refKind())
                .minValue(request.minValue())
                .maxValue(request.maxValue())
                .build();
        definition.replaceKinds(request.kinds());
        definition = attributeDefinitionRepository.save(definition);
        labelService.createAll(LabelReferent.attribute(definition.getId()), request.labels());

        auditLogService.record(actingAdminId, "ATTRIBUTE_DEFINITION_CREATE", null, describe(definition));
        return toResponse(definition);
    }

    @Transactional
    public AttributeDefinitionResponse update(UUID actingAdminId, UUID definitionId,
            UpdateAttributeDefinitionRequest request) {
        AttributeDefinition definition = requireDefinition(definitionId);
        requireGroup(request.groupId());
        // The code computing a derived value relies on its type, and no payload can carry it, so
        // making it required would refuse every revision of its kinds.
        if (definition.isDerived() && (definition.getValueType() != request.valueType()
                || Boolean.TRUE.equals(request.isRequired()))) {
            throw new AttributeIsDerivedException("definition " + definitionId + " is derived");
        }
        // An ENUM's options would be left describing a field that no longer has options.
        if (definition.getValueType() == AttributeValueType.ENUM && request.valueType() != AttributeValueType.ENUM
                && attributeOptionRepository.existsByAttributeId(definitionId)) {
            throw new AttributeHasOptionsException("definition " + definitionId + " has options");
        }
        requireFactsStillFit(definition, request);
        String before = describe(definition);

        definition.setValueType(request.valueType());
        definition.setUnit(request.unit());
        definition.setGroupId(request.groupId());
        definition.setSort(Objects.requireNonNullElse(request.sort(), 0));
        definition.setMulti(Boolean.TRUE.equals(request.isMulti()));
        definition.setRequired(Boolean.TRUE.equals(request.isRequired()));
        definition.setInfobox(Boolean.TRUE.equals(request.isInfobox()));
        definition.setFilterable(Boolean.TRUE.equals(request.isFilterable()));
        definition.setBadge(Boolean.TRUE.equals(request.isBadge()));
        definition.setRefKind(request.refKind());
        definition.setMinValue(request.minValue());
        definition.setMaxValue(request.maxValue());
        definition.setDeprecated(Boolean.TRUE.equals(request.isDeprecated()));
        definition.replaceKinds(request.kinds());
        // Flushed so updated_at and any constraint failure surface inside this call.
        definition = attributeDefinitionRepository.saveAndFlush(definition);

        auditLogService.record(actingAdminId, "ATTRIBUTE_DEFINITION_UPDATE", null,
                "before={" + before + "}, after={" + describe(definition) + "}");
        return toResponse(definition);
    }

    @Transactional
    public AttributeOptionResponse createOption(UUID actingAdminId, UUID definitionId,
            CreateAttributeOptionRequest request) {
        AttributeDefinition definition = requireDefinition(definitionId);
        if (definition.getValueType() != AttributeValueType.ENUM) {
            throw new AttributeOptionsNotSupportedException(
                    "definition " + definitionId + " is " + definition.getValueType() + ", not ENUM");
        }
        if (attributeOptionRepository.existsByAttributeIdAndCode(definitionId, request.code())) {
            throw new DuplicateAttributeOptionCodeException(
                    "option code " + request.code() + " already exists on definition " + definitionId);
        }

        AttributeOption option = attributeOptionRepository.save(AttributeOption.builder()
                .attributeId(definitionId)
                .code(request.code())
                .sort(Objects.requireNonNullElse(request.sort(), 0))
                .build());
        labelService.createAll(LabelReferent.option(option.getId()), request.labels());

        auditLogService.record(actingAdminId, "ATTRIBUTE_OPTION_CREATE", null, describe(definition, option));
        return toResponse(option);
    }

    @Transactional
    public AttributeOptionResponse updateOption(UUID actingAdminId, UUID definitionId, UUID optionId,
            UpdateAttributeOptionRequest request) {
        AttributeDefinition definition = requireDefinition(definitionId);
        AttributeOption option = attributeOptionRepository.findByIdAndAttributeId(optionId, definitionId)
                .orElseThrow(() -> new AttributeOptionNotFoundException(
                        "option " + optionId + " not found on definition " + definitionId));
        String before = describe(definition, option);
        option.setSort(Objects.requireNonNullElse(request.sort(), 0));
        option.setDeprecated(Boolean.TRUE.equals(request.isDeprecated()));

        auditLogService.record(actingAdminId, "ATTRIBUTE_OPTION_UPDATE", null,
                "before={" + before + "}, after={" + describe(definition, option) + "}");
        return toResponse(option);
    }

    // Published facts were checked against the definition as it was; a change that would make
    // any of them unfit is refused rather than left to surface as a broken infobox or a facet
    // that miscounts. Pending revisions are not checked here -- their submit and approval
    // re-validate against the definition as it is then.
    private void requireFactsStillFit(AttributeDefinition definition, UpdateAttributeDefinitionRequest request) {
        UUID definitionId = definition.getId();
        boolean isRetyped = definition.getValueType() != request.valueType()
                || !Objects.equals(definition.getRefKind(), request.refKind());
        if (isRetyped && subjectFactService.hasFacts(definitionId)) {
            throw new AttributeHasFactsException("definition " + definitionId + " has facts; its type cannot change");
        }
        if (definition.isMulti() && !Boolean.TRUE.equals(request.isMulti())
                && subjectFactService.hasSubjectWithSeveralPreferredValues(definitionId)) {
            throw new AttributeHasFactsException("definition " + definitionId + " has subjects with several values");
        }
        Set<SubjectKind> removedKinds = definition.getKinds().isEmpty()
                ? EnumSet.noneOf(SubjectKind.class) : EnumSet.copyOf(definition.getKinds());
        removedKinds.removeAll(request.kinds());
        if (subjectFactService.hasFactsOnKinds(definitionId, removedKinds)) {
            throw new AttributeHasFactsException(
                    "definition " + definitionId + " has facts on removed kinds " + removedKinds);
        }
    }

    private AttributeDefinition requireDefinition(UUID definitionId) {
        return attributeDefinitionRepository.findById(definitionId)
                .orElseThrow(() -> new AttributeDefinitionNotFoundException(
                        "attribute definition not found: " + definitionId));
    }

    private void requireGroup(UUID groupId) {
        if (!attributeGroupRepository.existsById(groupId)) {
            throw new AttributeGroupNotFoundException("attribute group not found: " + groupId);
        }
    }

    private AttributeDefinitionResponse toResponse(AttributeDefinition definition) {
        return new AttributeDefinitionResponse(definition.getId(), definition.getKey(), definition.getValueType(),
                definition.getUnit(), definition.getGroupId(), definition.getSort(), definition.isMulti(),
                definition.isRequired(), definition.isInfobox(), definition.isFilterable(), definition.getRefKind(),
                definition.getMinValue(), definition.getMaxValue(), definition.isDeprecated(), definition.isDerived(),
                definition.isBadge(), definition.getKinds().stream().sorted().toList(),
                labelService.values(LabelReferent.attribute(definition.getId())));
    }

    private AttributeOptionResponse toResponse(AttributeOption option) {
        return new AttributeOptionResponse(option.getId(), option.getAttributeId(), option.getCode(), option.getSort(),
                option.isDeprecated(), labelService.values(LabelReferent.option(option.getId())));
    }

    private static String describe(AttributeDefinition definition) {
        return "id=" + definition.getId() + ", key=" + definition.getKey() + ", valueType=" + definition.getValueType()
                + ", unit=" + definition.getUnit() + ", groupId=" + definition.getGroupId()
                + ", sort=" + definition.getSort() + ", isMulti=" + definition.isMulti()
                + ", isRequired=" + definition.isRequired() + ", isInfobox=" + definition.isInfobox()
                + ", isFilterable=" + definition.isFilterable() + ", refKind=" + definition.getRefKind()
                + ", minValue=" + definition.getMinValue() + ", maxValue=" + definition.getMaxValue()
                + ", isDeprecated=" + definition.isDeprecated() + ", isBadge=" + definition.isBadge()
                + ", kinds=" + definition.getKinds().stream().sorted().toList();
    }

    private static String describe(AttributeDefinition definition, AttributeOption option) {
        return "attributeKey=" + definition.getKey() + ", optionId=" + option.getId() + ", code=" + option.getCode()
                + ", sort=" + option.getSort() + ", isDeprecated=" + option.isDeprecated();
    }
}
