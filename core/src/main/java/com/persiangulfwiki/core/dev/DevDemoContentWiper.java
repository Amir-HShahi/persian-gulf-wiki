package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.service.SubjectArticleService;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.source.repository.SourceRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;
import com.persiangulfwiki.core.subject.service.SubjectInfoboxCacheService;
import com.persiangulfwiki.core.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

// Removes the demo dataset, and only it: every statement below is filtered on DevDemoContent's
// marker or its email prefix and domain, so a hand-made row and an E2E fixture are never touched.
//
// Content attached *to* demo content goes with it through the schema's CASCADEs (a revision someone
// wrote on a demo article, a photo uploaded to one). Content that demo content is attached *to*
// stays, and keeps what it needs: a demo subject that a hand-made article or fact still points at
// is left in place (SubjectRepository.KEPT_MARKED_SUBJECTS), and so is a demo account
// that still authored something that outlived the wipe (UserRepository.deleteUnreferencedBy...).
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
class DevDemoContentWiper {

    private final ArticleMediaRepository articleMediaRepository;
    private final ArticleRepository articleRepository;
    private final SubjectRevisionRepository subjectRevisionRepository;
    private final SubjectRepository subjectRepository;
    private final SourceRepository sourceRepository;
    private final UserRepository userRepository;
    private final SubjectArticleService subjectArticleService;
    private final SubjectInfoboxCacheService subjectInfoboxCacheService;
    private final DevDemoMediaLibrary mediaLibrary;
    private final PlatformTransactionManager transactionManager;

    // isLibraryRemoved: false on a reseed, which uploads the same files again straight after.
    void wipe(boolean isLibraryRemoved) {
        // Storage first, rows second, the order MediaCleanupJob uses: a storage delete cannot be
        // rolled back, so if the database step then fails, the rows are still there to point the
        // next wipe at the same prefixes (both deletes are idempotent).
        List<ArticleMedia> media = articleMediaRepository.findByDevMarker(DevDemoContent.MARKER);
        media.forEach(item -> mediaLibrary.deleteItemFiles(item.getId()));

        // Every createdAt the seeder writes is in the past, so this matches every demo row.
        Instant everything = Instant.now().plus(Duration.ofDays(1));
        new TransactionTemplate(transactionManager).executeWithoutResult(_ -> {
            // Articles before subjects: articles.subject_id has no ON DELETE action. Translations,
            // revisions, moderation tasks, gallery items and featured windows cascade from them.
            long articles = articleRepository.deleteByDevMarkerAndCreatedAtBefore(DevDemoContent.MARKER, everything);
            // Proposals that never got a subject; every other revision goes with its subject, and
            // expert verifications with their revision.
            subjectRevisionRepository.deleteUnpublishedByDevMarkerAndCreatedAtBefore(DevDemoContent.MARKER, everything);
            subjectRepository.deleteFactsOfMarkedSubjectsExceptKept(DevDemoContent.MARKER);
            int subjects = subjectRepository.deleteByDevMarkerExceptKept(DevDemoContent.MARKER);
            long sources = sourceRepository.deleteByDevMarkerAndCreatedAtBefore(DevDemoContent.MARKER, everything);
            int users = userRepository.deleteUnreferencedByEmailPrefixAndDomain(DevDemoContent.EMAIL_PREFIX,
                    DevDemoContent.EMAIL_DOMAIN);
            // A kept subject may just have lost its main article.
            subjectArticleService.promoteWhereMissing();
            log.info("wiped demo content: {} articles, {} subjects, {} sources, {} users, {} gallery items", articles,
                    subjects, sources, users, media.size());
        });
        if (isLibraryRemoved) {
            mediaLibrary.deleteLibrary();
        }
        subjectInfoboxCacheService.evictAll();
    }
}
