package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.repository.FeaturedArticleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestFeaturedArticleController's DELETE route, the same role DevTestTopicSweeper
// plays for minted topics. A minted window on a minted article also goes when the article sweep
// deletes that article (ON DELETE CASCADE, V34); this catches the ones on real articles.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestFeaturedArticleSweeper {

    private final FeaturedArticleRepository featuredArticleRepository;

    @Value("${app.dev.test-featured-article-ttl-hours}")
    private final long testFeaturedArticleTtlHours;

    // :05, the one free slot; nothing else depends on these rows. @Transactional here for the same
    // self-invocation reason as DevTestSubjectSweeper.sweepExpiredTestSubjects.
    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void sweepExpiredTestFeaturedArticles() {
        deleteOlderThan(Instant.now().minus(testFeaturedArticleTtlHours, ChronoUnit.HOURS));
    }

    @Transactional
    public long deleteOlderThan(Instant threshold) {
        long deleted = featuredArticleRepository.deleteByDevMarkerAndCreatedAtBefore(DevTestFixtures.MARKER, threshold);
        if (deleted > 0) {
            log.info("swept {} minted featured-article window(s) older than the {}h TTL", deleted,
                    testFeaturedArticleTtlHours);
        }
        return deleted;
    }
}
