package com.persiangulfwiki.core.revisioncheck.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.jayway.jsonpath.JsonPath;
import com.persiangulfwiki.core.article.event.RevisionSubmittedEvent;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.jobqueue.entity.QueueJob;
import com.persiangulfwiki.core.jobqueue.repository.QueueJobRepository;
import com.persiangulfwiki.core.jobqueue.service.JobQueueService;
import com.persiangulfwiki.core.revisioncheck.dto.LinkResultResponse;
import com.persiangulfwiki.core.revisioncheck.dto.RevisionChecksResponse;
import com.persiangulfwiki.core.revisioncheck.entity.LinkFinding;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheck;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckFindings;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckStatus;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckType;
import com.persiangulfwiki.core.revisioncheck.entity.TextFinding;
import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The submit listener and every branch of applyResult, with the repositories mocked and a real
// JobQueueService (its parsing decides the branch, and its enqueue is what the listener calls).
@ExtendWith(MockitoExtension.class)
class RevisionCheckServiceTests {

    private static final long JOB_ID = 77L;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Mock
    private RevisionCheckRepository revisionCheckRepository;

    @Mock
    private QueueJobRepository queueJobRepository;

    @Mock
    private ArticleRevisionService articleRevisionService;

    private RevisionCheckService revisionCheckService;

    private final UUID revisionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        revisionCheckService = new RevisionCheckService(revisionCheckRepository,
                new JobQueueService(queueJobRepository, JSON), articleRevisionService);
    }

    // --- Fixtures ---

    private RevisionCheck pending(RevisionCheckType type) {
        return RevisionCheck.builder()
                .id(UUID.randomUUID())
                .revisionId(revisionId)
                .type(type)
                .status(RevisionCheckStatus.PENDING)
                .jobId(JOB_ID)
                .build();
    }

    private void stored(RevisionCheck check) {
        when(revisionCheckRepository.findByIdForUpdate(check.getId())).thenReturn(Optional.of(check));
    }

    private static Map<String, Object> envelope(String type, @Nullable String outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("job_id", JOB_ID);
        result.put("type", type);
        result.put("outcome", outcome);
        result.put("attempt", 1);
        return result;
    }

    private static Map<String, Object> finding(String rule) {
        Map<String, Object> finding = new HashMap<>();
        finding.put("rule", rule);
        finding.put("line", 2);
        finding.put("column", 4);
        finding.put("offset", 21);
        finding.put("found", "می شود");
        finding.put("suggest", "می‌شود");
        finding.put("context", "این جزیره دیده می شود");
        return finding;
    }

    private Map<String, Object> normalizeResult(List<Map<String, Object>> findings, @Nullable Integer count) {
        Map<String, Object> block = new HashMap<>();
        block.put("revision_id", revisionId.toString());
        block.put("locale", "fa");
        block.put("count", count);
        block.put("findings", findings);
        Map<String, Object> result = envelope("normalize", "ok");
        result.put("normalize", block);
        return result;
    }

    private static Map<String, Object> link(String url, String status) {
        Map<String, Object> link = new HashMap<>();
        link.put("url", url);
        link.put("status", status);
        return link;
    }

    private Map<String, Object> linksResult(List<Map<String, Object>> links, @Nullable Integer count,
            @Nullable Integer truncated) {
        Map<String, Object> block = new HashMap<>();
        block.put("revision_id", revisionId.toString());
        block.put("count", count);
        block.put("broken", 999);
        block.put("links", links);
        block.put("links_truncated", truncated);
        Map<String, Object> result = envelope("links", "ok");
        result.put("links", block);
        return result;
    }

    private void applyDone(RevisionCheck check, Object result) {
        String json = result instanceof String raw ? raw : JSON.writeValueAsString(result);
        revisionCheckService.applyResult(check.getId(), JOB_ID, QueueJob.STATUS_DONE, json, null);
    }

    private static void assertEnded(RevisionCheck check, RevisionCheckStatus status, @Nullable String reasonCode) {
        assertThat(check.getStatus()).isEqualTo(status);
        assertThat(check.getReasonCode()).isEqualTo(reasonCode);
        assertThat(check.getFinishedAt()).isNotNull();
        assertThat(check.getTotalCount()).isNull();
        assertThat(check.getProblemCount()).isNull();
        assertThat(check.getOmittedCount()).isNull();
        assertThat(check.getFindings()).isNull();
    }

    // --- onRevisionSubmitted ---

    private void enqueuesWithIncreasingIds() {
        AtomicLong ids = new AtomicLong(100);
        when(queueJobRepository.save(any(QueueJob.class))).thenAnswer(invocation -> {
            QueueJob job = invocation.getArgument(0);
            job.setId(ids.incrementAndGet());
            return job;
        });
    }

    @Test
    void aFirstSubmitCreatesBothChecksPendingWithAJobEach() {
        enqueuesWithIncreasingIds();

        revisionCheckService.onRevisionSubmitted(new RevisionSubmittedEvent(revisionId, true));

        ArgumentCaptor<QueueJob> jobs = ArgumentCaptor.forClass(QueueJob.class);
        verify(queueJobRepository, times(2)).save(jobs.capture());
        assertThat(jobs.getAllValues()).extracting(QueueJob::getType).containsExactly("normalize", "links");
        assertThat(jobs.getAllValues()).allSatisfy(job ->
                assertThat((String) JsonPath.read(job.getPayload(), "$.revision_id")).isEqualTo(revisionId.toString()));

        ArgumentCaptor<RevisionCheck> checks = ArgumentCaptor.forClass(RevisionCheck.class);
        verify(revisionCheckRepository, times(2)).save(checks.capture());
        assertThat(checks.getAllValues()).extracting(RevisionCheck::getType)
                .containsExactly(RevisionCheckType.NORMALIZE, RevisionCheckType.LINKS);
        assertThat(checks.getAllValues()).extracting(RevisionCheck::getJobId).containsExactly(101L, 102L);
        assertThat(checks.getAllValues()).allSatisfy(check -> {
            assertThat(check.getRevisionId()).isEqualTo(revisionId);
            assertThat(check.getStatus()).isEqualTo(RevisionCheckStatus.PENDING);
        });
    }

    // A resubmit after REQUEST_CHANGES changed the text, so the previous findings describe
    // nothing any more; a dev-minted row is taken over by the real submit.
    @Test
    void aResubmitResetsTheExistingRowsAndDropsTheirFindingsAndMarker() {
        enqueuesWithIncreasingIds();
        RevisionCheck text = pending(RevisionCheckType.NORMALIZE);
        text.setStatus(RevisionCheckStatus.DONE);
        text.setTotalCount(2);
        text.setProblemCount(2);
        text.setOmittedCount(0);
        text.setFindings(new RevisionCheckFindings(List.of(new TextFinding("arabic_yeh", 1, 1, 0, "ي", "ی", "")),
                List.of()));
        text.setFinishedAt(Instant.now());
        RevisionCheck links = pending(RevisionCheckType.LINKS);
        links.setStatus(RevisionCheckStatus.REJECTED);
        links.setReasonCode("malformed_body");
        links.setFinishedAt(Instant.now());
        links.setDevMarker("e2e");
        when(revisionCheckRepository.findByRevisionIdAndType(revisionId, RevisionCheckType.NORMALIZE))
                .thenReturn(Optional.of(text));
        when(revisionCheckRepository.findByRevisionIdAndType(revisionId, RevisionCheckType.LINKS))
                .thenReturn(Optional.of(links));

        revisionCheckService.onRevisionSubmitted(new RevisionSubmittedEvent(revisionId, false));

        for (RevisionCheck check : List.of(text, links)) {
            assertThat(check.getStatus()).isEqualTo(RevisionCheckStatus.PENDING);
            assertThat(check.getJobId()).isNotEqualTo(JOB_ID);
            assertThat(check.getReasonCode()).isNull();
            assertThat(check.getTotalCount()).isNull();
            assertThat(check.getProblemCount()).isNull();
            assertThat(check.getOmittedCount()).isNull();
            assertThat(check.getFindings()).isNull();
            assertThat(check.getFinishedAt()).isNull();
            assertThat(check.getDevMarker()).isNull();
            verify(revisionCheckRepository).save(check);
        }
        assertThat(text.getJobId()).isEqualTo(101L);
        assertThat(links.getJobId()).isEqualTo(102L);
    }

    // --- Rows that are not this check's to apply ---

    @Test
    void ignoresAResultForACheckThatNoLongerExists() {
        UUID gone = UUID.randomUUID();
        when(revisionCheckRepository.findByIdForUpdate(gone)).thenReturn(Optional.empty());

        revisionCheckService.applyResult(gone, JOB_ID, QueueJob.STATUS_DONE, "{}", null);

        verify(revisionCheckRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = RevisionCheckStatus.class, names = {"DONE", "FAILED", "REJECTED"})
    void ignoresAResultForACheckNoLongerPending(RevisionCheckStatus status) {
        RevisionCheck check = pending(RevisionCheckType.NORMALIZE);
        check.setStatus(status);
        stored(check);

        applyDone(check, normalizeResult(List.of(finding("arabic_yeh")), 1));

        assertThat(check.getStatus()).isEqualTo(status);
        verify(revisionCheckRepository, never()).save(any());
    }

    // An earlier submit's late result must never overwrite the newer submit's pending check.
    @Test
    void ignoresAResultFromAnEarlierSubmitsJob() {
        RevisionCheck check = pending(RevisionCheckType.LINKS);
        check.setJobId(JOB_ID + 1);
        stored(check);

        applyDone(check, linksResult(List.of(link("https://example.org/", "broken")), 1, 0));

        assertThat(check.getStatus()).isEqualTo(RevisionCheckStatus.PENDING);
        verify(revisionCheckRepository, never()).save(any());
    }

    @Test
    void leavesTheCheckPendingWhileItsJobIsNotFinished() {
        RevisionCheck check = pending(RevisionCheckType.NORMALIZE);
        stored(check);

        revisionCheckService.applyResult(check.getId(), JOB_ID, QueueJob.STATUS_RUNNING, null, null);

        assertThat(check.getStatus()).isEqualTo(RevisionCheckStatus.PENDING);
        verify(revisionCheckRepository, never()).save(any());
    }

    // --- FAILED ---

    @Test
    void aFailedJobFailsTheCheck() {
        RevisionCheck check = pending(RevisionCheckType.LINKS);
        stored(check);

        revisionCheckService.applyResult(check.getId(), JOB_ID, QueueJob.STATUS_FAILED, null, "e".repeat(5000));

        assertEnded(check, RevisionCheckStatus.FAILED, null);
        verify(revisionCheckRepository).save(check);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{\"version\": \"one\"}", "{bad"})
    void anUnreadableResultFailsTheCheck(String resultJson) {
        RevisionCheck check = pending(RevisionCheckType.NORMALIZE);
        stored(check);

        applyDone(check, resultJson);

        assertEnded(check, RevisionCheckStatus.FAILED, null);
    }

    static Stream<Arguments> envelopeProblems() {
        return Stream.of(
                Arguments.of("version 2", (Consumer<Map<String, Object>>) result -> result.put("version", 2)),
                Arguments.of("no version", (Consumer<Map<String, Object>>) result -> result.remove("version")),
                Arguments.of("another job", (Consumer<Map<String, Object>>) result -> result.put("job_id", JOB_ID + 1)),
                Arguments.of("links result on a text check",
                        (Consumer<Map<String, Object>>) result -> result.put("type", "links")),
                Arguments.of("unknown outcome", (Consumer<Map<String, Object>>) result -> result.put("outcome", "skipped")),
                Arguments.of("no outcome", (Consumer<Map<String, Object>>) result -> result.remove("outcome")),
                Arguments.of("no normalize block", (Consumer<Map<String, Object>>) result -> result.remove("normalize")),
                Arguments.of("another revision", (Consumer<Map<String, Object>>) result -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> block = (Map<String, Object>) result.get("normalize");
                    block.put("revision_id", UUID.randomUUID().toString());
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("envelopeProblems")
    void aResultThatDoesNotFitTheCheckFailsIt(String problem, Consumer<Map<String, Object>> change) {
        RevisionCheck check = pending(RevisionCheckType.NORMALIZE);
        stored(check);
        Map<String, Object> result = normalizeResult(List.of(finding("arabic_yeh")), 1);
        change.accept(result);

        applyDone(check, result);

        assertEnded(check, RevisionCheckStatus.FAILED, null);
    }

    @Test
    void aLinksResultWithoutItsBlockOrForAnotherRevisionFailsTheCheck() {
        RevisionCheck noBlock = pending(RevisionCheckType.LINKS);
        stored(noBlock);
        applyDone(noBlock, envelope("links", "ok"));
        assertEnded(noBlock, RevisionCheckStatus.FAILED, null);

        RevisionCheck otherRevision = pending(RevisionCheckType.LINKS);
        stored(otherRevision);
        Map<String, Object> result = linksResult(List.of(), 0, 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) result.get("links");
        block.put("revision_id", null);
        applyDone(otherRevision, result);
        assertEnded(otherRevision, RevisionCheckStatus.FAILED, null);
    }

    // --- REJECTED ---

    @Test
    void aRejectionKeepsAWellFormedReasonCode() {
        RevisionCheck check = pending(RevisionCheckType.NORMALIZE);
        stored(check);
        Map<String, Object> result = envelope("normalize", "rejected");
        result.put("reason", Map.of("code", "unsupported_locale", "detail", Map.of("locale", "tr", "rules", 0)));

        applyDone(check, result);

        assertEnded(check, RevisionCheckStatus.REJECTED, "unsupported_locale");
    }

    // The code is shown to the author and moderators, so anything not shaped like the worker's
    // snake_case codes is replaced, never stored.
    @ParameterizedTest
    @ValueSource(strings = {"Unsupported_Locale", "<script>", "has space", "dash-code", ""})
    void aRejectionWithAMalformedOrOverlongCodeIsStoredAsGenericRejected(String code) {
        RevisionCheck check = pending(RevisionCheckType.LINKS);
        stored(check);
        Map<String, Object> result = envelope("links", "rejected");
        result.put("reason", Map.of("code", code));

        applyDone(check, result);

        assertEnded(check, RevisionCheckStatus.REJECTED, "rejected");
    }

    @Test
    void aRejectionWithoutAReasonOrWithASixtyOneCharacterCodeIsStoredAsGenericRejected() {
        RevisionCheck noReason = pending(RevisionCheckType.LINKS);
        stored(noReason);
        applyDone(noReason, envelope("links", "rejected"));
        assertEnded(noReason, RevisionCheckStatus.REJECTED, "rejected");

        RevisionCheck overlong = pending(RevisionCheckType.NORMALIZE);
        stored(overlong);
        Map<String, Object> result = envelope("normalize", "rejected");
        result.put("reason", Map.of("code", "a".repeat(61)));
        applyDone(overlong, result);
        assertEnded(overlong, RevisionCheckStatus.REJECTED, "rejected");

        RevisionCheck sixty = pending(RevisionCheckType.NORMALIZE);
        stored(sixty);
        result.put("reason", Map.of("code", "a".repeat(60)));
        applyDone(sixty, result);
        assertEnded(sixty, RevisionCheckStatus.REJECTED, "a".repeat(60));
    }

    // --- DONE: text findings ---

    @Test
    void aNormalizeResultStoresItsFindingsAndCounts() {
        RevisionCheck check = pending(RevisionCheckType.NORMALIZE);
        stored(check);

        applyDone(check, normalizeResult(List.of(finding("detached_prefix"), finding("arabic_yeh")), 2));

        assertThat(check.getStatus()).isEqualTo(RevisionCheckStatus.DONE);
        assertThat(check.getReasonCode()).isNull();
        assertThat(check.getFinishedAt()).isNotNull();
        assertThat(check.getTotalCount()).isEqualTo(2);
        assertThat(check.getProblemCount()).isEqualTo(2);
        assertThat(check.getOmittedCount()).isZero();
        assertThat(check.getFindings()).isNotNull();
        assertThat(check.getFindings().links()).isEmpty();
        assertThat(check.getFindings().textFindings()).containsExactly(
                new TextFinding("detached_prefix", 2, 4, 21, "می شود", "می‌شود", "این جزیره دیده می شود"),
                new TextFinding("arabic_yeh", 2, 4, 21, "می شود", "می‌شود", "این جزیره دیده می شود"));
        verify(revisionCheckRepository).save(check);
    }

    // Finding text is kept as sent, invisible characters included -- they are often the finding
    // -- but cut by code points; a finding with a malformed rule is dropped, negative positions
    // become null.
    @Test
    void textFindingsAreSanitizedBeforeTheyAreStored() {
        RevisionCheck check = pending(RevisionCheckType.NORMALIZE);
        stored(check);
        String emoji = "📷";
        Map<String, Object> invisible = finding("zero_width_space");
        invisible.put("found", "​");
        invisible.put("suggest", "");
        Map<String, Object> overlong = finding("latin_quotes");
        overlong.put("context", "a".repeat(499) + emoji + emoji);
        overlong.put("line", -1);
        overlong.put("column", null);
        Map<String, Object> badRule = finding("Bad Rule");
        Map<String, Object> noRule = finding("x");
        noRule.remove("rule");
        List<Map<String, Object>> findings = new ArrayList<>(List.of(invisible, overlong, badRule, noRule));
        findings.add(null);

        applyDone(check, normalizeResult(findings, 5));

        assertThat(check.getStatus()).isEqualTo(RevisionCheckStatus.DONE);
        assertThat(check.getFindings().textFindings()).extracting(TextFinding::rule)
                .containsExactly("zero_width_space", "latin_quotes");
        TextFinding first = check.getFindings().textFindings().getFirst();
        assertThat(first.found()).isEqualTo("​");
        assertThat(first.suggest()).isEmpty();
        TextFinding cut = check.getFindings().textFindings().get(1);
        assertThat(cut.context()).isEqualTo("a".repeat(499) + emoji);
        assertThat(cut.line()).isNull();
        assertThat(cut.column()).isNull();
        assertThat(cut.offset()).isEqualTo(21);
        // The worker counted five; two are listed.
        assertThat(check.getTotalCount()).isEqualTo(5);
        assertThat(check.getProblemCount()).isEqualTo(5);
        assertThat(check.getOmittedCount()).isEqualTo(3);
    }

    // At most 500 findings are stored whatever the worker sends; a count lower than what was
    // listed is not believed.
    @Test
    void textFindingsAreCappedAtFiveHundredAndTheCountNeverUnderstatesTheList() {
        RevisionCheck capped = pending(RevisionCheckType.NORMALIZE);
        stored(capped);
        List<Map<String, Object>> many = IntStream.range(0, 600).mapToObj(_ -> finding("arabic_yeh")).toList();
        applyDone(capped, normalizeResult(many, 650));
        assertThat(capped.getFindings().textFindings()).hasSize(RevisionCheckService.MAX_TEXT_FINDINGS);
        assertThat(capped.getTotalCount()).isEqualTo(650);
        assertThat(capped.getOmittedCount()).isEqualTo(150);

        RevisionCheck understated = pending(RevisionCheckType.NORMALIZE);
        stored(understated);
        applyDone(understated, normalizeResult(List.of(finding("a"), finding("b"), finding("c")), 1));
        assertThat(understated.getTotalCount()).isEqualTo(3);
        assertThat(understated.getOmittedCount()).isZero();

        RevisionCheck uncounted = pending(RevisionCheckType.NORMALIZE);
        stored(uncounted);
        applyDone(uncounted, normalizeResult(List.of(finding("a")), null));
        assertThat(uncounted.getTotalCount()).isEqualTo(1);
    }

    @Test
    void aNormalizeResultWithNoFindingsIsDoneWithZeroCounts() {
        RevisionCheck check = pending(RevisionCheckType.NORMALIZE);
        stored(check);
        Map<String, Object> result = normalizeResult(List.of(), 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) result.get("normalize");
        block.remove("findings");

        applyDone(check, result);

        assertThat(check.getStatus()).isEqualTo(RevisionCheckStatus.DONE);
        assertThat(check.getTotalCount()).isZero();
        assertThat(check.getProblemCount()).isZero();
        assertThat(check.getFindings().textFindings()).isEmpty();
    }

    // --- DONE: links ---

    // problemCount is what core stores as broken or unreachable -- never the worker's own number,
    // and never `restricted`, which only means the site refuses robots.
    @Test
    void aLinksResultCountsOnlyStoredBrokenAndUnreachableLinks() {
        RevisionCheck check = pending(RevisionCheckType.LINKS);
        stored(check);
        Map<String, Object> broken = link("https://example.org/gone", "broken");
        broken.put("http_status", 404);
        broken.put("archive_url", "https://web.archive.org/web/2019/https://example.org/gone");
        Map<String, Object> moved = link("http://old.example.org/", "redirected");
        moved.put("http_status", 301);
        moved.put("final_url", "https://new.example.org/");
        Map<String, Object> down = link("https://down.example.org/", "unreachable");
        down.put("error", "timeout");
        Map<String, Object> robots = link("https://example.org/members", "restricted");
        robots.put("http_status", 403);
        List<Map<String, Object>> links = List.of(link("https://example.org/ok", "ok"), moved, broken, down, robots,
                link("https://10.0.0.1/", "blocked"), link("https://example.org/late", "unchecked"));

        applyDone(check, linksResult(links, 7, 3));

        assertThat(check.getStatus()).isEqualTo(RevisionCheckStatus.DONE);
        assertThat(check.getTotalCount()).isEqualTo(7);
        assertThat(check.getProblemCount()).isEqualTo(2);
        assertThat(check.getOmittedCount()).isEqualTo(3);
        assertThat(check.getFindings().textFindings()).isEmpty();
        assertThat(check.getFindings().links()).containsExactly(
                new LinkFinding("https://example.org/ok", "ok", null, null, null, null),
                new LinkFinding("http://old.example.org/", "redirected", 301, "https://new.example.org/", null, null),
                new LinkFinding("https://example.org/gone", "broken", 404, null,
                        "https://web.archive.org/web/2019/https://example.org/gone", null),
                new LinkFinding("https://down.example.org/", "unreachable", null, null, null, "timeout"),
                new LinkFinding("https://example.org/members", "restricted", 403, null, null, null),
                new LinkFinding("https://10.0.0.1/", "blocked", null, null, null, null),
                new LinkFinding("https://example.org/late", "unchecked", null, null, null, null));
    }

    // All three URLs are shown as links: a link whose own url is not http(s) is dropped whole, a
    // bad finalUrl/archiveUrl is nulled; a malformed status drops the link, a malformed error or
    // an impossible HTTP status is nulled.
    @Test
    void linksAreSanitizedBeforeTheyAreStored() {
        RevisionCheck check = pending(RevisionCheckType.LINKS);
        stored(check);
        Map<String, Object> badFinal = link("https://example.org/a", "redirected");
        badFinal.put("final_url", "javascript:alert(1)");
        badFinal.put("http_status", 99);
        Map<String, Object> badArchive = link("https://example.org/b", "broken");
        badArchive.put("archive_url", "ftp://archive.example.org/b");
        badArchive.put("error", "Not A Code");
        badArchive.put("http_status", 600);
        Map<String, Object> noUrl = link("x", "ok");
        noUrl.remove("url");
        List<Map<String, Object>> links = new ArrayList<>(List.of(
                link("javascript:alert(1)", "broken"),
                link("mailto:a@example.org", "broken"),
                link("https:///no-host", "broken"),
                link("https://example.org/" + "a".repeat(2100), "broken"),
                link("https://example.org/c", "Broken Status"),
                noUrl,
                badFinal,
                badArchive));
        links.add(null);

        applyDone(check, linksResult(links, 9, null));

        assertThat(check.getFindings().links()).containsExactly(
                new LinkFinding("https://example.org/a", "redirected", null, null, null, null),
                new LinkFinding("https://example.org/b", "broken", null, null, null, null));
        assertThat(check.getProblemCount()).isEqualTo(1);
        assertThat(check.getTotalCount()).isEqualTo(9);
        assertThat(check.getOmittedCount()).isZero();
    }

    @Test
    void linksAreCappedAtOneHundred() {
        RevisionCheck check = pending(RevisionCheckType.LINKS);
        stored(check);
        List<Map<String, Object>> many = IntStream.range(0, 150)
                .mapToObj(i -> link("https://example.org/" + i, "broken")).toList();

        applyDone(check, linksResult(many, 50, -4));

        assertThat(check.getFindings().links()).hasSize(RevisionCheckService.MAX_LINKS);
        assertThat(check.getProblemCount()).isEqualTo(100);
        // A count below what is listed is not believed, and a negative truncation is none.
        assertThat(check.getTotalCount()).isEqualTo(100);
        assertThat(check.getOmittedCount()).isZero();
    }

    // --- Reads ---

    @Test
    void getForRevisionMapsBothChecksAndMarksOnlyBrokenAndUnreachableLinksBroken() {
        RevisionCheck links = pending(RevisionCheckType.LINKS);
        links.setStatus(RevisionCheckStatus.DONE);
        links.setTotalCount(3);
        links.setProblemCount(2);
        links.setOmittedCount(0);
        links.setFindings(new RevisionCheckFindings(List.of(), List.of(
                new LinkFinding("https://a.example/", "broken", 404, null, null, null),
                new LinkFinding("https://b.example/", "unreachable", null, null, null, "timeout"),
                new LinkFinding("https://c.example/", "restricted", 403, null, null, null))));
        when(revisionCheckRepository.findByRevisionId(revisionId)).thenReturn(List.of(links));

        RevisionChecksResponse response = revisionCheckService.getForRevision(revisionId);

        assertThat(response.revisionId()).isEqualTo(revisionId);
        assertThat(response.textCheck()).isNull();
        assertThat(response.linkCheck()).isNotNull();
        assertThat(response.linkCheck().brokenCount()).isEqualTo(2);
        assertThat(response.linkCheck().links()).extracting(LinkResultResponse::isBroken)
                .containsExactly(true, true, false);
    }

    @Test
    void getForRevisionOfARevisionNeverCheckedHasNeitherCheck() {
        when(revisionCheckRepository.findByRevisionId(revisionId)).thenReturn(List.of());

        RevisionChecksResponse response = revisionCheckService.getForRevision(revisionId);

        assertThat(response.textCheck()).isNull();
        assertThat(response.linkCheck()).isNull();
    }

    @Test
    void getForCallerReadsTheRevisionTheAccessCheckScoped() {
        UUID articleId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        when(articleRevisionService.requireChecksReadableBy(articleId, "fa", revisionId, caller, false))
                .thenReturn(revisionId);
        RevisionCheck text = pending(RevisionCheckType.NORMALIZE);
        when(revisionCheckRepository.findByRevisionId(revisionId)).thenReturn(List.of(text));

        RevisionChecksResponse response = revisionCheckService.getForCaller(caller, false, articleId, "fa", revisionId);

        assertThat(response.textCheck()).isNotNull();
        assertThat(response.textCheck().status()).isEqualTo(RevisionCheckStatus.PENDING);
        assertThat(response.textCheck().findings()).isEmpty();
        assertThat(response.textCheck().checkedAt()).isNull();
    }
}
