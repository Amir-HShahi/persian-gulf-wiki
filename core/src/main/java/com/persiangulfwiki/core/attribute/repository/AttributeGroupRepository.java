package com.persiangulfwiki.core.attribute.repository;

import com.persiangulfwiki.core.attribute.entity.AttributeGroup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AttributeGroupRepository extends JpaRepository<AttributeGroup, UUID> {

    boolean existsByKey(String key);

    Optional<AttributeGroup> findFirstByOrderBySortAscKeyAsc();

    // Skips a group that still holds a definition: attribute_definitions.group_id is RESTRICT,
    // so the bulk delete would otherwise abort on the first such row.
    @Modifying
    @Query("""
            delete from AttributeGroup g
            where g.devMarker = :devMarker and g.createdAt < :threshold
              and not exists (select 1 from AttributeDefinition d where d.groupId = g.id)
            """)
    int deleteUnusedByDevMarkerAndCreatedAtBefore(@Param("devMarker") String devMarker,
            @Param("threshold") Instant threshold);
}
