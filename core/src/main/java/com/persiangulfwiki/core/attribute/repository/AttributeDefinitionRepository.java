package com.persiangulfwiki.core.attribute.repository;

import com.persiangulfwiki.core.attribute.entity.AttributeDefinition;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttributeDefinitionRepository extends JpaRepository<AttributeDefinition, UUID> {

    boolean existsByKey(String key);

    Optional<AttributeDefinition> findByKey(String key);

    boolean existsByGroupId(UUID groupId);

    @Query("select d from AttributeDefinition d join d.kinds k where k = :kind")
    List<AttributeDefinition> findByKind(@Param("kind") SubjectKind kind);

    @Query("select distinct d from AttributeDefinition d join d.kinds k where k in :kinds")
    List<AttributeDefinition> findByKindIn(@Param("kinds") Collection<SubjectKind> kinds);

    // Derived deletes load and remove each row, so the kinds collection goes through JPA;
    // options and labels go through V22's ON DELETE CASCADE.
    long deleteByGroupIdAndDevMarker(UUID groupId, String devMarker);

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);
}
