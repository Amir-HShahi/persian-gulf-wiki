package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.entity.ArticleRevision;
import com.persiangulfwiki.core.article.entity.ArticleTranslation;
import com.persiangulfwiki.core.article.entity.FeaturedArticle;
import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.article.entity.TranslationState;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.repository.ArticleRevisionRepository;
import com.persiangulfwiki.core.article.repository.ArticleTranslationRepository;
import com.persiangulfwiki.core.article.repository.FeaturedArticleRepository;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoArticle;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoAsset;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoFact;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoFactValue;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoSource;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoSubject;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoTranslation;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoUser;
import com.persiangulfwiki.core.dev.DevDemoDataset.Localized;
import com.persiangulfwiki.core.dev.dto.DevDemoContentResponse;
import com.persiangulfwiki.core.dev.dto.DevDemoContentRunStatus;
import com.persiangulfwiki.core.dev.dto.DevDemoContentStatusResponse;
import com.persiangulfwiki.core.dev.exception.DevDemoContentBusyException;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaDescription;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaDescriptionRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.moderation.entity.ModerationTask;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.source.entity.Source;
import com.persiangulfwiki.core.source.repository.SourceRepository;
import com.persiangulfwiki.core.subject.dto.LocalizedTextRequest;
import com.persiangulfwiki.core.subject.dto.SubjectAliasesRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactRequest;
import com.persiangulfwiki.core.subject.dto.SubjectFactValueRequest;
import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.dto.VerificationState;
import com.persiangulfwiki.core.subject.entity.ExpertVerification;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectRevision;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;
import com.persiangulfwiki.core.subject.repository.ExpertVerificationRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRevisionRepository;
import com.persiangulfwiki.core.subject.service.SubjectRevisionService;
import com.persiangulfwiki.core.topic.entity.Topic;
import com.persiangulfwiki.core.topic.repository.TopicRepository;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// Builds the demo dataset (DevDemoDataset) into the database and the media bucket, behind
// POST /api/dev/demo-content. Unlike the DevTest* fixtures, which write single rows directly, this
// goes through the real approval paths wherever those are what writes a read model:
//   - SubjectRevisionService.applyModerationOutcome validates each infobox payload against the field
//     vocabulary and writes facts, aliases, labels and derived fields (has_360_tour, density);
//   - ArticleRevisionService.applyModerationOutcome publishes each revision and promotes the main
//     article, exactly as a moderator's approval would.
// A dataset entry the real rules would refuse therefore fails the seed loudly, naming the entry,
// rather than producing a page no real content could produce.
//
// Order matters, and is the one a real subject's life would follow: subject r1 (no image yet) ->
// its articles -> their galleries published -> subject r2 naming a published image as the main
// image, which SubjectPayloadValidationService only accepts at that point.
//
// Each step runs in its own transaction: a 500-article run as one transaction would hold every row
// lock for the whole seed. A run that fails part-way leaves demo rows behind, which the next POST
// wipes before it starts.
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
class DevDemoContentSeeder {

    private static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);
    private static final Duration HISTORY_SPAN = Duration.ofDays(730);
    private static final int MAX_REVISIONS = 8;
    private static final int MAX_CONTRIBUTORS = 11;
    private static final String SLUG_CLASH_SUFFIX = "-demo";
    // Share of articles that get a pending fa revision (with an open moderation task), and of the
    // rest that get one sent back with changes requested, so the moderator screens are not empty.
    private static final double PENDING_SHARE = 0.05;
    private static final double CHANGES_REQUESTED_SHARE = 0.03;
    // When an entry does not say: about 60% VERIFIED, 10% STALE, the rest NONE.
    private static final double VERIFIED_SHARE = 0.6;
    private static final double STALE_SHARE = 0.1;

    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;
    private final DevDemoContentWiper wiper;
    private final DevDemoMediaLibrary mediaLibrary;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SourceRepository sourceRepository;
    private final TopicRepository topicRepository;
    private final SubjectRevisionRepository subjectRevisionRepository;
    private final SubjectRevisionService subjectRevisionService;
    private final ExpertVerificationRepository expertVerificationRepository;
    private final ArticleRepository articleRepository;
    private final ArticleTranslationRepository articleTranslationRepository;
    private final ArticleRevisionRepository articleRevisionRepository;
    private final ArticleRevisionService articleRevisionService;
    private final ModerationTaskRepository moderationTaskRepository;
    private final ArticleMediaRepository articleMediaRepository;
    private final MediaMetadataVersionRepository mediaMetadataVersionRepository;
    private final MediaDescriptionRepository mediaDescriptionRepository;
    private final FeaturedArticleRepository featuredArticleRepository;

    // One seed or wipe at a time: two overlapping runs would each wipe the other's half-built
    // rows. A flag rather than a lock, because the second caller should be told (409), not queued.
    private final AtomicBoolean isRunning = new AtomicBoolean();

    private static final DevDemoContentStatusResponse IDLE = new DevDemoContentStatusResponse(
            DevDemoContentRunStatus.IDLE, null, 0, 0, null, null, null, null);

    // The latest run, replaced as a whole on every change so a reader never sees half an update.
    private final AtomicReference<DevDemoContentStatusResponse> status = new AtomicReference<>(IDLE);

    // One thread: runs never overlap (isRunning sees to that), and a fill is cheap enough not to
    // need more. Owned here rather than shared with the mail executor, so a long fill never delays
    // a verification email.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("demo-content").daemon().factory());

    // Parsed once; the files are on the classpath and cannot change while the app runs.
    private volatile @Nullable DevDemoDataset dataset;

    // Starts a fill on the seeder's own thread and returns at once: a full run takes several seconds,
    // longer than a caller should hold a request open. The status says how far it got.
    DevDemoContentStatusResponse start() {
        if (!isRunning.compareAndSet(false, true)) {
            throw new DevDemoContentBusyException();
        }
        Instant startedAt = Instant.now();
        status.set(new DevDemoContentStatusResponse(DevDemoContentRunStatus.RUNNING, "Starting", 0, 0, startedAt,
                null, null, null));
        try {
            executor.execute(() -> runSeed(startedAt));
        } catch (RuntimeException ex) {
            // Never queued, so nothing will release the flag but this.
            isRunning.set(false);
            throw ex;
        }
        return status.get();
    }

    DevDemoContentStatusResponse status() {
        return status.get();
    }

    void wipe() {
        if (!isRunning.compareAndSet(false, true)) {
            throw new DevDemoContentBusyException();
        }
        try {
            wiper.wipe(true);
            status.set(IDLE);
        } finally {
            isRunning.set(false);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    private void runSeed(Instant startedAt) {
        try {
            DevDemoContentResponse result = seed(startedAt);
            status.set(new DevDemoContentStatusResponse(DevDemoContentRunStatus.SUCCEEDED, null, 0, 0, startedAt,
                    Instant.now(), result, null));
        } catch (RuntimeException ex) {
            // Logged here and only here: there is no request left for the exception handler to answer.
            log.error("demo content seed failed", ex);
            status.set(new DevDemoContentStatusResponse(DevDemoContentRunStatus.FAILED, null, 0, 0, startedAt,
                    Instant.now(), null, ex.getMessage()));
        } finally {
            isRunning.set(false);
        }
    }

    private DevDemoContentResponse seed(Instant startedAt) {
        long started = System.nanoTime();
        DevDemoDataset demo = dataset();
        progress(startedAt, "Removing earlier demo content", 0, 0);
        wiper.wipe(false);
        progress(startedAt, "Uploading the image library", 0, 0);
        mediaLibrary.upload(demo.assets());
        Run run = new Run(demo);
        progress(startedAt, "Creating accounts and sources", 0, 0);
        seedUsers(run);
        seedSources(run);
        List<DemoArticle> subjects = subjectOrder(demo);
        for (int i = 0; i < subjects.size(); i++) {
            progress(startedAt, "Creating subjects", i, subjects.size());
            DemoArticle article = subjects.get(i);
            inTransaction(() -> seedSubjectProposal(run, article));
        }
        List<DemoArticle> articles = demo.articles();
        for (int i = 0; i < articles.size(); i++) {
            progress(startedAt, "Writing articles", i, articles.size());
            DemoArticle article = articles.get(i);
            inTransaction(() -> seedArticle(run, article));
        }
        for (int i = 0; i < articles.size(); i++) {
            progress(startedAt, "Publishing galleries", i, articles.size());
            DemoArticle article = articles.get(i);
            inTransaction(() -> seedGallery(run, article));
        }
        for (int i = 0; i < subjects.size(); i++) {
            progress(startedAt, "Setting main images", i, subjects.size());
            DemoArticle article = subjects.get(i);
            inTransaction(() -> seedSubjectMainImage(run, article));
        }
        progress(startedAt, "Adding verifications and featured articles", 0, 0);
        inTransaction(() -> seedVerifications(run));
        inTransaction(() -> seedFeaturedWindows(run));
        long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        log.info("seeded demo content: {} articles, {} subjects in {} ms", run.articleIds.size(),
                run.subjectIds.size(), durationMs);
        return run.response(durationMs);
    }

    private void progress(Instant startedAt, String step, int done, int total) {
        status.set(new DevDemoContentStatusResponse(DevDemoContentRunStatus.RUNNING, step, done, total, startedAt,
                null, null, null));
    }

    private DevDemoDataset dataset() {
        DevDemoDataset loaded = dataset;
        if (loaded == null) {
            loaded = DevDemoDataset.load(objectMapper);
            dataset = loaded;
        }
        return loaded;
    }

    private void inTransaction(Runnable step) {
        new TransactionTemplate(transactionManager).executeWithoutResult(_ -> step.run());
    }

    // ---------------------------------------------------------------------------------------- users

    private void seedUsers(Run run) {
        inTransaction(() -> {
            // One hash for every account: they share the documented password, and bcrypt per
            // account would add seconds to every seed for nothing.
            String passwordHash = passwordEncoder.encode(DevUserSeeder.DEV_PASSWORD);
            for (DemoUser demoUser : run.demo.users()) {
                String email = DevDemoContent.EMAIL_PREFIX + demoUser.key() + DevDemoContent.EMAIL_DOMAIN;
                // Kept by an earlier wipe when it still authored something hand-made: reuse it.
                User user = userRepository.findByEmail(email).orElseGet(() -> {
                    User created = userRepository.save(User.builder()
                            .username(DevDemoContent.USERNAME_PREFIX + demoUser.key().replace('-', '_'))
                            .email(email)
                            .passwordHash(passwordHash)
                            .emailVerified(true)
                            .enabled(true)
                            .build());
                    saveRole(created, Role.CONTRIBUTOR, null);
                    if (demoUser.role() != Role.CONTRIBUTOR) {
                        saveRole(created, demoUser.role(), demoUser.expertise());
                    }
                    run.usersCreated++;
                    return created;
                });
                switch (demoUser.role()) {
                    case EXPERT_REVIEWER -> run.experts.put(demoUser.expertise(), user.getId());
                    case MODERATOR, ADMIN -> run.moderatorId = user.getId();
                    case CONTRIBUTOR -> run.contributors.add(user.getId());
                }
            }
            if (run.moderatorId == null) {
                run.moderatorId = run.contributors.getFirst();
            }
        });
    }

    private void saveRole(User user, Role role, @Nullable SubjectKind expertise) {
        userRoleRepository.save(UserRole.builder().user(user).role(role).expertise(expertise).build());
    }

    // -------------------------------------------------------------------------------------- sources

    private void seedSources(Run run) {
        inTransaction(() -> {
            for (DemoSource demoSource : run.demo.sources()) {
                Source source = sourceRepository.save(Source.builder()
                        .title(demoSource.title())
                        .publisher(demoSource.publisher())
                        .url(demoSource.url())
                        .publishedOn(demoSource.publishedOn())
                        .createdByUserId(run.pick(run.contributors))
                        .devMarker(DevDemoContent.MARKER)
                        .build());
                run.sourceIds.put(demoSource.key(), source.getId());
            }
        });
    }

    // ------------------------------------------------------------------------------------- subjects

    // Subjects whose facts point at other subjects come after those subjects (a province before its
    // islands): the approval checks that a referenced subject exists. Depth-first, keyed order.
    private static List<DemoArticle> subjectOrder(DevDemoDataset demo) {
        Map<String, DemoArticle> byKey = demo.articlesByKey();
        List<DemoArticle> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (DemoArticle article : demo.articles()) {
            if (article.hasSubject()) {
                visit(article, byKey, done, visiting, ordered);
            }
        }
        return ordered;
    }

    private static void visit(DemoArticle article, Map<String, DemoArticle> byKey, Set<String> done,
            Set<String> visiting, List<DemoArticle> ordered) {
        if (done.contains(article.key())) {
            return;
        }
        if (!visiting.add(article.key())) {
            throw new IllegalStateException("demo subjects reference each other in a cycle at " + article.key());
        }
        for (DemoFact fact : article.subject().facts()) {
            for (DemoFactValue value : fact.values()) {
                if (value.subject() != null) {
                    visit(byKey.get(value.subject()), byKey, done, visiting, ordered);
                }
            }
        }
        visiting.remove(article.key());
        done.add(article.key());
        ordered.add(article);
    }

    private void seedSubjectProposal(Run run, DemoArticle article) {
        DemoSubject subject = article.subject();
        Instant createdAt = run.articleCreatedAt(article.key());
        SubjectRevision revision = subjectRevisionRepository.saveAndFlush(SubjectRevision.builder()
                .kind(subject.kind())
                .revisionNumber(1)
                .payload(objectMapper.writeValueAsString(payload(run, subject, null)))
                .summary("Initial proposal")
                // Stored PENDING and approved for real below, as DevTestSubjectRevisionController
                // does: the approval is what checks the payload and writes the read model.
                .status(SubjectRevisionStatus.PENDING)
                .authorId(run.pick(run.contributors))
                .createdAt(createdAt)
                .devMarker(DevDemoContent.MARKER)
                .build());
        approveSubjectRevision(article, revision.getId());
        UUID subjectId = subjectRevisionRepository.findById(revision.getId()).orElseThrow().getSubjectId();
        run.subjectIds.put(article.key(), subjectId);
        run.firstSubjectRevisionIds.put(article.key(), revision.getId());
        run.subjectsByKind.merge(subject.kind(), 1, Integer::sum);
    }

    private void seedSubjectMainImage(Run run, DemoArticle article) {
        UUID baseRevisionId = run.firstSubjectRevisionIds.get(article.key());
        SubjectRevision revision = subjectRevisionRepository.saveAndFlush(SubjectRevision.builder()
                .subjectId(run.subjectIds.get(article.key()))
                .kind(article.subject().kind())
                .revisionNumber(2)
                .baseRevisionId(baseRevisionId)
                .payload(objectMapper.writeValueAsString(
                        payload(run, article.subject(), run.mainImageIds.get(article.key()))))
                .summary("Main image")
                .status(SubjectRevisionStatus.PENDING)
                .authorId(run.pick(run.contributors))
                .createdAt(run.articleCreatedAt(article.key()).plus(Duration.ofDays(1)))
                .devMarker(DevDemoContent.MARKER)
                .build());
        approveSubjectRevision(article, revision.getId());
        run.liveSubjectRevisionIds.put(article.key(), revision.getId());
    }

    private void approveSubjectRevision(DemoArticle article, UUID revisionId) {
        try {
            subjectRevisionService.applyModerationOutcome(revisionId, SubjectRevisionStatus.APPROVED);
        } catch (RuntimeException ex) {
            // The real approval refused the entry: say which one, the bare violation list does not.
            throw new IllegalStateException("demo subject " + article.key() + " was refused by the approval: "
                    + ex.getMessage(), ex);
        }
    }

    private static SubjectPayloadRequest payload(Run run, DemoSubject subject, @Nullable UUID mainImageMediaId) {
        List<SubjectFactRequest> facts = new ArrayList<>();
        for (DemoFact fact : subject.facts()) {
            List<SubjectFactValueRequest> values = new ArrayList<>();
            for (DemoFactValue value : fact.values()) {
                values.add(new SubjectFactValueRequest(value.number(), value.option(),
                        value.subject() != null ? run.subjectIds.get(value.subject()) : null, value.bool(),
                        value.date(), value.text(), localized(value.localized()), value.asOf(),
                        value.source() != null ? run.sourceIds.get(value.source()) : null, value.rank()));
            }
            facts.add(new SubjectFactRequest(fact.attribute(), values));
        }
        SubjectAliasesRequest aliases = subject.aliases() != null
                ? new SubjectAliasesRequest(subject.aliases().fa(), subject.aliases().en(), subject.aliases().ar())
                : null;
        return new SubjectPayloadRequest(localized(subject.names()), aliases, facts, subject.geometry(),
                mainImageMediaId);
    }

    private static @Nullable LocalizedTextRequest localized(@Nullable Localized text) {
        return text != null ? new LocalizedTextRequest(text.fa(), text.en(), text.ar()) : null;
    }

    // ------------------------------------------------------------------------------------- articles

    private void seedArticle(Run run, DemoArticle demoArticle) {
        Instant createdAt = run.articleCreatedAt(demoArticle.key());
        Instant lastEditedAt = createdAt.plus(Duration.ofMillis(
                (long) (run.random.nextDouble() * Duration.between(createdAt, run.now).toMillis())));
        int revisions = demoArticle.history() != null && demoArticle.history().revisions() != null
                ? demoArticle.history().revisions()
                : 1 + run.random.nextInt(MAX_REVISIONS);
        int contributors = demoArticle.history() != null && demoArticle.history().contributors() != null
                ? demoArticle.history().contributors()
                : 1 + run.random.nextInt(Math.min(MAX_CONTRIBUTORS, revisions * 3));
        List<UUID> authors = run.pickDistinct(run.contributors, contributors);
        Set<UUID> topicIds = topicIds(run, demoArticle);
        List<String> outdated = demoArticle.history() != null && demoArticle.history().outdated() != null
                ? demoArticle.history().outdated()
                : List.of();

        Article article = articleRepository.save(Article.builder()
                .subjectId(demoArticle.hasSubject() ? run.subjectIds.get(demoArticle.key()) : null)
                .canonicalLanguage(demoArticle.canonicalLanguage())
                .createdByUserId(authors.getFirst())
                .isMain(false)
                .devMarker(DevDemoContent.MARKER)
                .build());
        run.articleIds.put(demoArticle.key(), article.getId());

        // Canonical first: the other translations record its live revision as their source.
        List<String> languages = new ArrayList<>(DevDemoDataset.LANGUAGES);
        languages.remove(demoArticle.canonicalLanguage());
        languages.addFirst(demoArticle.canonicalLanguage());
        UUID canonicalRevisionId = null;
        int authorSlot = 0;
        for (String language : languages) {
            DemoTranslation demoTranslation = demoArticle.translations().get(language);
            boolean isCanonical = canonicalRevisionId == null;
            ArticleTranslation translation = articleTranslationRepository.save(ArticleTranslation.builder()
                    .articleId(article.getId())
                    .language(language)
                    .slug(freeSlug(demoTranslation.slug()))
                    .translationState(outdated.contains(language) ? TranslationState.OUTDATED : TranslationState.UP_TO_DATE)
                    .build());
            UUID parentId = null;
            for (int number = 1; number <= revisions; number++) {
                ArticleRevision revision = ArticleRevision.builder()
                        .translationId(translation.getId())
                        .revisionNumber(number)
                        .parentRevisionId(parentId)
                        .title(demoTranslation.title())
                        // Earlier revisions carry fewer sections, so the history reads as an
                        // article that grew rather than the same text saved eight times.
                        .body(objectMapper.writeValueAsString(DevDemoBodyBuilder.build(
                                partial(demoTranslation, number, revisions), run.subjectIds, run.sourceIds)))
                        .summary(number == 1 ? "First version" : "Expanded and sourced")
                        .status(RevisionStatus.PENDING)
                        .authorId(authors.get(authorSlot++ % authors.size()))
                        .createdAt(between(createdAt, lastEditedAt, number, revisions))
                        .build();
                revision.setTopicIds(topicIds);
                revision = articleRevisionRepository.save(revision);
                articleRevisionService.applyModerationOutcome(revision.getId(), RevisionStatus.APPROVED);
                parentId = revision.getId();
                run.revisions++;
            }
            if (isCanonical) {
                canonicalRevisionId = parentId;
            } else {
                translation.setSourceRevisionId(canonicalRevisionId);
                articleTranslationRepository.save(translation);
            }
            run.translations++;
            if (isCanonical) {
                seedOpenRevision(run, translation.getId(), parentId, revisions + 1, demoTranslation, topicIds);
            }
        }
        articleRepository.backdate(article.getId(), createdAt, lastEditedAt);
    }

    // A few articles also have an unpublished canonical revision on top: one awaiting a moderator
    // (with its open task) or one sent back to its author.
    private void seedOpenRevision(Run run, UUID translationId, UUID parentId, int number, DemoTranslation text,
            Set<UUID> topicIds) {
        double roll = run.random.nextDouble();
        RevisionStatus status = roll < PENDING_SHARE ? RevisionStatus.PENDING
                : roll < PENDING_SHARE + CHANGES_REQUESTED_SHARE ? RevisionStatus.CHANGES_REQUESTED
                : null;
        if (status == null) {
            return;
        }
        ArticleRevision revision = ArticleRevision.builder()
                .translationId(translationId)
                .revisionNumber(number)
                .parentRevisionId(parentId)
                .title(text.title())
                .body(objectMapper.writeValueAsString(DevDemoBodyBuilder.build(text, run.subjectIds, run.sourceIds)))
                .summary("Copy edit")
                .status(status)
                .authorId(run.pick(run.contributors))
                .build();
        revision.setTopicIds(topicIds);
        revision = articleRevisionRepository.save(revision);
        if (status == RevisionStatus.PENDING) {
            moderationTaskRepository.save(ModerationTask.builder()
                    .revisionId(revision.getId())
                    .state(ModerationTaskState.OPEN)
                    .devMarker(DevDemoContent.MARKER)
                    .build());
        }
        run.pendingRevisions++;
    }

    private Set<UUID> topicIds(Run run, DemoArticle article) {
        Set<UUID> ids = new HashSet<>();
        for (String slug : article.topics()) {
            ids.add(run.topicIds.computeIfAbsent(slug, key -> topicRepository.findBySlugAndIsArchivedFalse(key)
                    .map(Topic::getId)
                    .orElseThrow(() -> new IllegalStateException("demo article " + article.key()
                            + " names no active topic " + key))));
        }
        return ids;
    }

    // Slugs are unique across every article and language; one already taken by content that is not
    // the demo's (made by hand, or by an E2E suite) gets a suffix rather than failing the seed.
    private String freeSlug(String slug) {
        String candidate = slug;
        while (articleTranslationRepository.existsBySlug(candidate)) {
            candidate = candidate + SLUG_CLASH_SUFFIX;
        }
        return candidate;
    }

    private static DemoTranslation partial(DemoTranslation text, int number, int revisions) {
        if (number == revisions) {
            return text;
        }
        int sections = Math.max(1, (int) Math.ceil(text.sections().size() * (double) number / revisions));
        return new DemoTranslation(text.slug(), text.title(), text.summary(), text.lead(),
                text.sections().subList(0, sections));
    }

    private static Instant between(Instant from, Instant to, int number, int count) {
        if (count == 1) {
            return to;
        }
        long span = Duration.between(from, to).toMillis();
        return from.plusMillis(span * (number - 1) / (count - 1));
    }

    // -------------------------------------------------------------------------------------- gallery

    private void seedGallery(Run run, DemoArticle demoArticle) {
        UUID articleId = run.articleIds.get(demoArticle.key());
        Map<String, DemoAsset> assets = run.assets;
        Point location = demoArticle.hasSubject() ? centroid(demoArticle.subject().geometry()) : null;
        List<String> gallery = demoArticle.gallery() != null ? demoArticle.gallery() : List.of();
        for (String assetKey : gallery) {
            UUID mediaId = publish(run, articleId, assets.get(assetKey), location);
            run.mainImageIds.putIfAbsent(demoArticle.key(), mediaId);
            run.images++;
        }
        if (demoArticle.panorama() != null) {
            publish(run, articleId, assets.get(demoArticle.panorama()), location);
            run.panoramas++;
        }
    }

    // A gallery item as the real flow leaves it once the pipeline has processed it and a moderator
    // has approved its first metadata version: READY, PUBLISHED, files under its public prefix.
    private UUID publish(Run run, UUID articleId, DemoAsset asset, @Nullable Point location) {
        UUID uploaderId = run.pick(run.contributors);
        Instant publishedAt = run.now.minus(Duration.ofDays(run.random.nextInt(600)));
        long largest = asset.variants().stream().mapToLong(variant -> variant.bytes()).max().orElse(0);
        ArticleMedia media = articleMediaRepository.save(ArticleMedia.builder()
                .articleId(articleId)
                .type(asset.kind())
                .uploadedBy(uploaderId)
                .declaredContentType("image/jpeg")
                .declaredBytes(largest)
                .declaredSha256(asset.sha256())
                .sha256(asset.sha256())
                .processingStatus(ProcessingStatus.READY)
                // PENDING until its first metadata version exists:
                // ck_article_media_published_has_metadata refuses a PUBLISHED row without one.
                .publicationStatus(PublicationStatus.PENDING)
                .variants(new ArrayList<>())
                .placeholder(asset.placeholder())
                .devMarker(DevDemoContent.MARKER)
                .build());
        MediaMetadataVersion version = mediaMetadataVersionRepository.save(MediaMetadataVersion.builder()
                .mediaId(media.getId())
                .versionNumber(1)
                .submittedBy(uploaderId)
                .status(MetadataVersionStatus.APPROVED)
                .shotAt(publishedAt.minus(Duration.ofDays(run.random.nextInt(30))))
                .location(location)
                .build());
        for (String language : DevDemoDataset.LANGUAGES) {
            mediaDescriptionRepository.save(MediaDescription.builder()
                    .metadataVersionId(version.getId())
                    .language(language)
                    .text(asset.caption().in(language))
                    .build());
        }
        media.setCurrentMetadataVersionId(version.getId());
        media.setPublishedAt(publishedAt);
        media.setPublicationStatus(PublicationStatus.PUBLISHED);
        media.setVariants(mediaLibrary.copyTo(asset, media.getId()));
        articleMediaRepository.save(media);
        return media.getId();
    }

    private static @Nullable Point centroid(@Nullable String wkt) {
        if (wkt == null) {
            return null;
        }
        try {
            // A new reader per call: WKTReader holds parse state.
            Geometry geometry = new WKTReader(WGS84).read(wkt);
            Coordinate centre = geometry.getCentroid().getCoordinate();
            return WGS84.createPoint(centre);
        } catch (ParseException ex) {
            throw new IllegalStateException("demo geometry is not WKT: " + wkt, ex);
        }
    }

    // -------------------------------------------------------------------- verifications and featured

    private void seedVerifications(Run run) {
        for (DemoArticle article : run.demo.articles()) {
            if (!article.hasSubject()) {
                continue;
            }
            VerificationState state = article.verification() != null ? article.verification() : run.rollVerification();
            UUID revisionId = switch (state) {
                case VERIFIED -> run.liveSubjectRevisionIds.get(article.key());
                // Verified an older revision only: the infobox reports "verified r1, current r2".
                case STALE -> run.firstSubjectRevisionIds.get(article.key());
                case NONE -> null;
            };
            if (revisionId == null) {
                continue;
            }
            UUID expertId = run.experts.getOrDefault(article.subject().kind(), run.experts.values().stream()
                    .findFirst().orElse(run.moderatorId));
            expertVerificationRepository.save(ExpertVerification.builder()
                    .subjectRevisionId(revisionId)
                    .expertId(expertId)
                    .devMarker(DevDemoContent.MARKER)
                    .build());
            run.verifications++;
        }
    }

    // One window that started a week ago and one starting next week. Backdated on purpose: the live
    // window is the one that started most recently (V34), so a window an E2E suite creates after the
    // seed wins over this one.
    private void seedFeaturedWindows(Run run) {
        // `featured` is an order: 1 is the window live now, 2 the one scheduled next.
        List<UUID> featured = run.demo.articles().stream()
                .filter(article -> article.featured() != null)
                .sorted(Comparator.comparingInt(DemoArticle::featured))
                .map(article -> run.articleIds.get(article.key()))
                .toList();
        List<UUID> candidates = featured.isEmpty() ? List.copyOf(run.articleIds.values()) : featured;
        if (candidates.isEmpty()) {
            return;
        }
        featuredArticleRepository.save(FeaturedArticle.builder()
                .articleId(candidates.getFirst())
                .startsAt(run.now.minus(Duration.ofDays(7)))
                .endsAt(run.now.plus(Duration.ofDays(7)))
                .featuredByUserId(run.moderatorId)
                .devMarker(DevDemoContent.MARKER)
                .build());
        run.featuredWindows++;
        if (candidates.size() > 1) {
            featuredArticleRepository.save(FeaturedArticle.builder()
                    .articleId(candidates.get(1))
                    .startsAt(run.now.plus(Duration.ofDays(7)))
                    .endsAt(run.now.plus(Duration.ofDays(14)))
                    .featuredByUserId(run.moderatorId)
                    .devMarker(DevDemoContent.MARKER)
                    .build());
            run.featuredWindows++;
        }
    }

    // ------------------------------------------------------------------------------------------ run

    // Everything one seed accumulates: ids by dataset key, so later steps can resolve references,
    // and the counts the response reports.
    private static final class Run {

        private final DevDemoDataset demo;
        private final Map<String, DemoAsset> assets;
        // Seeded, so the same dataset yields the same picks every run -- see DevDemoContent.RANDOM_SEED.
        private final Random random = new Random(DevDemoContent.RANDOM_SEED);
        private final Instant now = Instant.now();
        private final Map<String, Instant> createdAt = new HashMap<>();
        private final List<UUID> contributors = new ArrayList<>();
        private final Map<SubjectKind, UUID> experts = new EnumMap<>(SubjectKind.class);
        private @Nullable UUID moderatorId;
        private final Map<String, UUID> sourceIds = new HashMap<>();
        private final Map<String, UUID> topicIds = new HashMap<>();
        private final Map<String, UUID> subjectIds = new HashMap<>();
        private final Map<String, UUID> firstSubjectRevisionIds = new HashMap<>();
        private final Map<String, UUID> liveSubjectRevisionIds = new HashMap<>();
        private final Map<String, UUID> articleIds = new LinkedHashMap<>();
        private final Map<String, UUID> mainImageIds = new HashMap<>();
        private final Map<SubjectKind, Integer> subjectsByKind = new EnumMap<>(SubjectKind.class);
        private int usersCreated;
        private int translations;
        private int revisions;
        private int pendingRevisions;
        private int images;
        private int panoramas;
        private int verifications;
        private int featuredWindows;

        private Run(DevDemoDataset demo) {
            this.demo = demo;
            this.assets = demo.assetsByKey();
            // Fixed up front, in key order, so an article's date does not depend on which step asks
            // for it first.
            for (DemoArticle article : demo.articles()) {
                long ageMillis = (long) (random.nextDouble() * HISTORY_SPAN.toMillis());
                createdAt.put(article.key(), now.minus(Duration.ofDays(2)).minusMillis(ageMillis));
            }
        }

        private Instant articleCreatedAt(String key) {
            return createdAt.get(key);
        }

        private UUID pick(List<UUID> from) {
            return from.get(random.nextInt(from.size()));
        }

        private List<UUID> pickDistinct(List<UUID> from, int count) {
            List<UUID> shuffled = new ArrayList<>(from);
            Collections.shuffle(shuffled, random);
            return List.copyOf(shuffled.subList(0, Math.max(1, Math.min(count, shuffled.size()))));
        }

        private VerificationState rollVerification() {
            double roll = random.nextDouble();
            return roll < VERIFIED_SHARE ? VerificationState.VERIFIED
                    : roll < VERIFIED_SHARE + STALE_SHARE ? VerificationState.STALE
                    : VerificationState.NONE;
        }

        private DevDemoContentResponse response(long durationMs) {
            return new DevDemoContentResponse(usersCreated, sourceIds.size(), Map.copyOf(subjectsByKind),
                    articleIds.size(), translations, revisions, pendingRevisions, images, panoramas, verifications,
                    featuredWindows, durationMs);
        }
    }
}
