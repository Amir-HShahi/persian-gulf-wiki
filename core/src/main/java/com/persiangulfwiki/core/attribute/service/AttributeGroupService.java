package com.persiangulfwiki.core.attribute.service;

import com.persiangulfwiki.core.attribute.dto.AttributeGroupResponse;
import com.persiangulfwiki.core.attribute.dto.CreateAttributeGroupRequest;
import com.persiangulfwiki.core.attribute.dto.UpdateAttributeGroupRequest;
import com.persiangulfwiki.core.attribute.entity.AttributeGroup;
import com.persiangulfwiki.core.attribute.exception.AttributeGroupNotFoundException;
import com.persiangulfwiki.core.attribute.exception.DuplicateAttributeGroupKeyException;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.label.service.LabelReferent;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.subject.service.SubjectInfoboxCacheService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

// Admin-only writes; every one is audit-logged and none is moderated. Each clears the cached
// infoboxes, which show group labels and order.
@Service
@RequiredArgsConstructor
public class AttributeGroupService {

    private final AttributeGroupRepository attributeGroupRepository;
    private final LabelService labelService;
    private final AuditLogService auditLogService;
    private final SubjectInfoboxCacheService subjectInfoboxCacheService;

    @Transactional
    public AttributeGroupResponse create(UUID actingAdminId, CreateAttributeGroupRequest request) {
        if (attributeGroupRepository.existsByKey(request.key())) {
            throw new DuplicateAttributeGroupKeyException("attribute group key already exists: " + request.key());
        }
        AttributeGroup group = attributeGroupRepository.save(AttributeGroup.builder()
                .key(request.key())
                .sort(Objects.requireNonNullElse(request.sort(), 0))
                .build());
        labelService.createAll(LabelReferent.group(group.getId()), request.labels());

        subjectInfoboxCacheService.evictAll();
        auditLogService.record(actingAdminId, "ATTRIBUTE_GROUP_CREATE", null, describe(group));
        return toResponse(group);
    }

    @Transactional
    public AttributeGroupResponse update(UUID actingAdminId, UUID groupId, UpdateAttributeGroupRequest request) {
        AttributeGroup group = attributeGroupRepository.findById(groupId)
                .orElseThrow(() -> new AttributeGroupNotFoundException("attribute group not found: " + groupId));
        String before = describe(group);
        group.setSort(Objects.requireNonNullElse(request.sort(), 0));

        subjectInfoboxCacheService.evictAll();
        auditLogService.record(actingAdminId, "ATTRIBUTE_GROUP_UPDATE", null,
                "before={" + before + "}, after={" + describe(group) + "}");
        return toResponse(group);
    }

    private AttributeGroupResponse toResponse(AttributeGroup group) {
        return new AttributeGroupResponse(group.getId(), group.getKey(), group.getSort(),
                labelService.values(LabelReferent.group(group.getId())));
    }

    private static String describe(AttributeGroup group) {
        return "id=" + group.getId() + ", key=" + group.getKey() + ", sort=" + group.getSort();
    }
}
