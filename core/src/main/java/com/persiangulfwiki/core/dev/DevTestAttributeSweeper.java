package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.attribute.repository.AttributeDefinitionRepository;
import com.persiangulfwiki.core.attribute.repository.AttributeGroupRepository;
import com.persiangulfwiki.core.subject.service.SubjectInfoboxCacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestAttributeController's DELETE routes, the same role DevTestSubjectSweeper
// plays for minted subjects. Minted options and every label on a minted row go with their
// definition or group through ON DELETE CASCADE (V22).
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestAttributeSweeper {

    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeGroupRepository attributeGroupRepository;
    private final SubjectInfoboxCacheService subjectInfoboxCacheService;

    @Value("${app.dev.test-attribute-ttl-hours}")
    private final long testAttributeTtlHours;

    // :45 is the free slot between the subject (:40) and source (:50) sweeps. @Transactional here
    // for the same self-invocation reason as DevTestSubjectSweeper.sweepExpiredTestSubjects.
    @Scheduled(cron = "0 45 * * * *")
    @Transactional
    public void sweepExpiredTestAttributes() {
        deleteOlderThan(Instant.now().minus(testAttributeTtlHours, ChronoUnit.HOURS));
    }

    // Definitions first: attribute_definitions.group_id is RESTRICT, so a minted group can only go
    // once its minted definitions have. A minted group still holding a real definition is skipped
    // rather than failing the whole sweep. Returns definitions + groups deleted.
    @Transactional
    public long deleteOlderThan(Instant threshold) {
        long definitions = attributeDefinitionRepository.deleteByDevMarkerAndCreatedAtBefore(
                DevTestFixtures.MARKER, threshold);
        // The derived delete above removes through the persistence context; flush it so the bulk
        // group delete below sees those definitions gone.
        attributeDefinitionRepository.flush();
        long groups = attributeGroupRepository.deleteUnusedByDevMarkerAndCreatedAtBefore(
                DevTestFixtures.MARKER, threshold);
        if (definitions + groups > 0) {
            log.info("swept {} minted test definition(s) and {} group(s) older than the {}h TTL",
                    definitions, groups, testAttributeTtlHours);
            // Cached infoboxes may show what was just deleted.
            subjectInfoboxCacheService.evictAll();
        }
        return definitions + groups;
    }
}
