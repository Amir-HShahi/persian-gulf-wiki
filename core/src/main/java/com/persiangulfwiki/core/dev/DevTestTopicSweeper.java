package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.topic.repository.TopicRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestTopicController's DELETE route, the same role DevTestSubjectSweeper plays for
// minted subjects. Labels go with their topic through ON DELETE CASCADE (V30).
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestTopicSweeper {

    private final TopicRepository topicRepository;

    @Value("${app.dev.test-topic-ttl-hours}")
    private final long testTopicTtlHours;

    // :25, after the article sweep at :20 has removed expired test articles and, with them, their
    // revisions' topic rows. A minted topic still tagging a revision is skipped (revision_topics.
    // topic_id is RESTRICT) rather than failing the whole pass. @Transactional here for the same
    // self-invocation reason as DevTestSubjectSweeper.sweepExpiredTestSubjects.
    @Scheduled(cron = "0 25 * * * *")
    @Transactional
    public void sweepExpiredTestTopics() {
        deleteOlderThan(Instant.now().minus(testTopicTtlHours, ChronoUnit.HOURS));
    }

    @Transactional
    public long deleteOlderThan(Instant threshold) {
        long deleted = topicRepository.deleteUnusedByDevMarkerAndCreatedAtBefore(DevTestFixtures.MARKER, threshold);
        if (deleted > 0) {
            log.info("swept {} minted test topic(s) older than the {}h TTL", deleted, testTopicTtlHours);
        }
        return deleted;
    }
}
