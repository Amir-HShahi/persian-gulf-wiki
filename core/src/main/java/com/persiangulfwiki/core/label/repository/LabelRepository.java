package com.persiangulfwiki.core.label.repository;

import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LabelRepository extends JpaRepository<Label, UUID> {

    List<Label> findByGroupIdIn(Collection<UUID> groupIds);

    List<Label> findByAttributeIdIn(Collection<UUID> attributeIds);

    List<Label> findByOptionIdIn(Collection<UUID> optionIds);

    List<Label> findBySubjectIdIn(Collection<UUID> subjectIds);

    List<Label> findByKindIn(Collection<SubjectKind> kinds);

    List<Label> findByTopicIdIn(Collection<UUID> topicIds);
}
