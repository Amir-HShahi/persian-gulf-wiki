package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.repository.ArticleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Backstop for DevTestArticleController's DELETE route -- see DevTestSubjectSweeper for the
// general reasoning, which is identical.
//
// Scheduling is enabled globally by OAuth2CleanupConfig, so this needs no @EnableScheduling
// of its own -- only the @Profile("dev") gate that keeps the bean from existing elsewhere.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevTestArticleSweeper {

    private final ArticleRepository articleRepository;

    @Value("${app.dev.test-article-ttl-hours}")
    private final long testArticleTtlHours;

    // Not just an arbitrary free slot: this MUST run before both DevTestUserSweeper (:30) and
    // DevTestSubjectSweeper (:40), not merely avoid colliding with them. articles.subject_id
    // references subjects (id) and article_revisions.author_id references users (id), and
    // neither FK is ON DELETE CASCADE/SET NULL (see V16's column comments) -- Postgres
    // defaults to RESTRICT. If either of those sweepers' bulk DELETE ran first, it would fail
    // outright on any subject/user still referenced by an expired-but-not-yet-swept article,
    // aborting that entire sweep pass for every row it touched, not just the blocked one.
    // Running this sweeper at :20 puts expired articles out of the way before :30/:40 run,
    // which covers the common case. It is not a complete guarantee, and the gap is worth
    // naming: this sweeper's threshold at :20 is ten and twenty minutes *stricter* than
    // theirs, so a subject created inside that trailing band is swept at :40 while an article
    // referencing it, being that much younger, survives :20 -- and subject_id's RESTRICT then
    // aborts the whole subject sweep pass. The author_id half of this race is already closed
    // (DevTestArticleController defaults to a never-swept seeded account; see
    // resolveAuthorUserId there), but the subject half is still open for a fixture article
    // explicitly bound to a dev-minted subject. Closing it properly means the subject and
    // user sweeps clearing their dependents first rather than three sweepers timing around
    // each other, which is a change to those classes and is deliberately not made here.
    //
    // @Transactional is on *this* method, not only on deleteOlderThan, and that is
    // load-bearing: the call below is a self-invocation, which does not pass back through the
    // proxy, so deleteOlderThan's own annotation is inert on this path.
    @Scheduled(cron = "0 20 * * * *")
    @Transactional
    public void sweepExpiredTestArticles() {
        deleteOlderThan(Instant.now().minus(testArticleTtlHours, ChronoUnit.HOURS));
    }

    // Split out from the scheduled trigger so the TTL boundary is testable by passing a
    // threshold, rather than by fabricating a createdAt -- which JPA cannot do anyway, since
    // created_at is mapped updatable = false and stamped in @PrePersist.
    @Transactional
    public long deleteOlderThan(Instant threshold) {
        // Matches on the dev marker, so an article a human created through the normal
        // endpoint is untouchable by this job no matter how old it gets -- its marker is
        // null. The translation and revision rows cascade with the article (ON DELETE
        // CASCADE, see V16), so no separate repository call is needed for them.
        long deleted = articleRepository.deleteByDevMarkerAndCreatedAtBefore(DevTestFixtures.MARKER, threshold);
        if (deleted > 0) {
            log.info("swept {} minted test article(s) older than the {}h TTL", deleted, testArticleTtlHours);
        }
        return deleted;
    }
}
