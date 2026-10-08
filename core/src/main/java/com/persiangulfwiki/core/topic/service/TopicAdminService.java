package com.persiangulfwiki.core.topic.service;

import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.label.service.LabelReferent;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.topic.dto.AdminTopicResponse;
import com.persiangulfwiki.core.topic.dto.CreateTopicRequest;
import com.persiangulfwiki.core.topic.dto.UpdateTopicRequest;
import com.persiangulfwiki.core.topic.entity.Topic;
import com.persiangulfwiki.core.topic.exception.DuplicateTopicSlugException;
import com.persiangulfwiki.core.topic.exception.TopicNotFoundException;
import com.persiangulfwiki.core.topic.repository.TopicRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

// Admin-only writes; every one is audit-logged and none is moderated, like the attribute
// vocabulary. There is no delete: a topic is retired by archiving it (see V30).
@Service
@RequiredArgsConstructor
public class TopicAdminService {

    private final TopicRepository topicRepository;
    private final LabelService labelService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<AdminTopicResponse> list() {
        return topicRepository.findAllByOrderBySortOrderAscSlugAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public AdminTopicResponse create(UUID actingAdminId, CreateTopicRequest request) {
        if (topicRepository.existsBySlug(request.slug())) {
            throw new DuplicateTopicSlugException("topic slug already exists: " + request.slug());
        }
        int sortOrder = request.sortOrder() != null ? request.sortOrder()
                : Objects.requireNonNullElse(topicRepository.findMaxSortOrder(), 0) + 1;
        Topic topic = topicRepository.save(Topic.builder()
                .slug(request.slug())
                .icon(request.icon())
                .color(normalizeColor(request.color()))
                .sortOrder(sortOrder)
                .build());
        labelService.createAll(LabelReferent.topic(topic.getId()), request.names());

        auditLogService.record(actingAdminId, "TOPIC_CREATE", null, describe(topic));
        return toResponse(topic);
    }

    @Transactional
    public AdminTopicResponse update(UUID actingAdminId, UUID topicId, UpdateTopicRequest request) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new TopicNotFoundException("topic not found: " + topicId));
        String before = describe(topic);
        topic.setIcon(request.icon());
        topic.setColor(normalizeColor(request.color()));
        topic.setSortOrder(request.sortOrder());
        topic.setArchived(request.isArchived());
        // Flushed so @PreUpdate has stamped updated_at before the response reads it.
        topic = topicRepository.saveAndFlush(topic);

        auditLogService.record(actingAdminId, "TOPIC_UPDATE", null,
                "before={" + before + "}, after={" + describe(topic) + "}");
        return toResponse(topic);
    }

    private AdminTopicResponse toResponse(Topic topic) {
        return new AdminTopicResponse(topic.getId(), topic.getSlug(), topic.getIcon(), topic.getColor(),
                topic.getSortOrder(), topic.isArchived(), labelService.values(LabelReferent.topic(topic.getId())),
                topic.getCreatedAt(), topic.getUpdatedAt());
    }

    private static String normalizeColor(String color) {
        return color.toUpperCase(Locale.ROOT);
    }

    private static String describe(Topic topic) {
        return "id=" + topic.getId() + ", slug=" + topic.getSlug() + ", icon=" + topic.getIcon() + ", color="
                + topic.getColor() + ", sortOrder=" + topic.getSortOrder() + ", isArchived=" + topic.isArchived();
    }
}
