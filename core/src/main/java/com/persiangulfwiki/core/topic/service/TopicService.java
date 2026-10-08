package com.persiangulfwiki.core.topic.service;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.topic.dto.TopicResponse;
import com.persiangulfwiki.core.topic.entity.Topic;
import com.persiangulfwiki.core.topic.exception.InvalidTopicIdsException;
import com.persiangulfwiki.core.topic.repository.TopicRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TopicService {

    private final TopicRepository topicRepository;
    private final LabelService labelService;

    /**
     * Archived topics are left out. {@code language} picks both whose published articles are counted
     * (matched exactly, like the article listing) and the names' language ({@code labelLanguage},
     * already one of fa/en/ar).
     */
    @Transactional(readOnly = true)
    public List<TopicResponse> list(String language, String labelLanguage) {
        List<Topic> topics = topicRepository.findAllByIsArchivedFalseOrderBySortOrderAscSlugAsc();
        Map<UUID, LocalizedLabelResponse> names =
                labelService.resolveTopics(topics.stream().map(Topic::getId).toList(), labelLanguage);
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : topicRepository.countPublishedArticlesByTopic(language)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return topics.stream()
                .map(topic -> new TopicResponse(topic.getId(), topic.getSlug(), topic.getIcon(),
                        topic.getColor(), names.get(topic.getId()), counts.getOrDefault(topic.getId(), 0L)))
                .toList();
    }

    /**
     * An archived topic is accepted only if it is in {@code alreadyOnRevision} -- a revision keeps the
     * topics it had, but no revision gains an archived one.
     *
     * @throws InvalidTopicIdsException if an id names no topic, or names an archived topic not in
     *     {@code alreadyOnRevision}
     */
    @Transactional(readOnly = true)
    public void requireAssignable(Set<UUID> requested, Set<UUID> alreadyOnRevision) {
        if (requested.isEmpty()) {
            return;
        }
        List<Topic> found = topicRepository.findAllByIdIn(requested);
        if (found.size() != requested.size()) {
            throw new InvalidTopicIdsException("unknown topic id in " + requested);
        }
        boolean isAddingArchived = found.stream()
                .anyMatch(topic -> topic.isArchived() && !alreadyOnRevision.contains(topic.getId()));
        if (isAddingArchived) {
            throw new InvalidTopicIdsException("archived topic id in " + requested, "validation.topicIds.archived");
        }
    }
}
