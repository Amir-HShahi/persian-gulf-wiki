package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestMediaController's DELETE route -- see DevTestSubjectSweeper for the
// general reasoning, which is identical.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestMediaSweeper {

    private final ArticleMediaRepository articleMediaRepository;

    @Value("${app.dev.test-media-ttl-hours}")
    private final long testMediaTtlHours;

    // :15 -- before DevTestUserSweeper at :30, which is the only ordering that matters here.
    // uploaded_by and media_metadata_versions.submitted_by are RESTRICT (V18), so an expired
    // fixture uploaded by a dev-minted user would abort that sweep's whole pass if it were still
    // around. Nothing points *at* article_media with RESTRICT (versions, links, moderation tasks
    // all cascade), so this pass can never be blocked itself, and the article sweep at :20
    // cascading into these rows is harmless either way. The controller defaults the uploader to
    // a never-swept seeded account, which closes the trailing-band race the same way
    // DevTestModerationController does.
    //
    // @Transactional here as well as on deleteOlderThan: the call below is a self-invocation.
    @Scheduled(cron = "0 15 * * * *")
    @Transactional
    public void sweepExpiredTestMedia() {
        deleteOlderThan(Instant.now().minus(testMediaTtlHours, ChronoUnit.HOURS));
    }

    @Transactional
    public long deleteOlderThan(Instant threshold) {
        // Marker-matched, so an item uploaded through the real flow is untouchable here. No
        // storage cleanup: fixtures never write objects.
        long deleted = articleMediaRepository.deleteByDevMarkerAndCreatedAtBefore(DevTestFixtures.MARKER, threshold);
        if (deleted > 0) {
            log.info("swept {} minted test gallery item(s) older than the {}h TTL", deleted, testMediaTtlHours);
        }
        return deleted;
    }
}
