package com.persiangulfwiki.core.revisioncheck.service;

import com.persiangulfwiki.core.article.event.RevisionSubmittedEvent;
import com.persiangulfwiki.core.article.service.ArticleRevisionService;
import com.persiangulfwiki.core.jobqueue.JobResult;
import com.persiangulfwiki.core.jobqueue.entity.QueueJob;
import com.persiangulfwiki.core.jobqueue.service.JobQueueService;
import com.persiangulfwiki.core.revisioncheck.dto.LinkCheckResponse;
import com.persiangulfwiki.core.revisioncheck.dto.LinkResultResponse;
import com.persiangulfwiki.core.revisioncheck.dto.RevisionChecksResponse;
import com.persiangulfwiki.core.revisioncheck.dto.TextCheckResponse;
import com.persiangulfwiki.core.revisioncheck.dto.TextFindingResponse;
import com.persiangulfwiki.core.revisioncheck.entity.LinkFinding;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheck;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckFindings;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckStatus;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckType;
import com.persiangulfwiki.core.revisioncheck.entity.TextFinding;
import com.persiangulfwiki.core.revisioncheck.repository.RevisionCheckRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

// The worker's two advisory checks on an article revision -- text fixes (normalize) and dead
// links (links) -- from the submit that queues them to the findings a moderator and the author
// read. Advisory only: nothing here is consulted by a moderation decision, and a check that fails
// or never finishes leaves the revision exactly as decidable as before.
//
//   submit   -> both rows reset to PENDING with a new job each, the previous findings dropped.
//               Every submit does it, the first and each resubmit after REQUEST_CHANGES, since
//               the text has changed.
//   finished -> DONE with the findings copied in (the worker deletes job rows after 30 days, and a
//               revision can wait longer for a moderator), REJECTED with the worker's reason, or
//               FAILED. A result is applied only to a PENDING row still waiting on that job, so an
//               earlier submit's late result never overwrites a newer one.
//
// The result is another process's output and is checked before it is stored: open-list codes
// must look like codes, every URL must be http(s) (all of them are shown as links), and text is
// cut to length. Finding text is otherwise kept as sent, invisible characters included, because
// an invisible character is often what the finding is about.
@Slf4j
@Service
@RequiredArgsConstructor
public class RevisionCheckService {

    // The worker's own caps: it lists at most 500 findings and checks at most 100 links.
    static final int MAX_TEXT_FINDINGS = 500;
    static final int MAX_LINKS = 100;
    static final int MAX_FINDING_TEXT_LENGTH = 500;
    static final int MAX_URL_LENGTH = 2048;
    // rule, status, error and reason codes: the worker's are all lowercase snake_case.
    private static final Pattern CODE = Pattern.compile("^[a-z0-9_]{1,60}$");
    private static final String GENERIC_REJECTION = "rejected";
    // What the worker counts in its own `broken`; "restricted" only means the site refuses robots.
    private static final Set<String> BROKEN_STATUSES = Set.of("broken", "unreachable");
    // The longest operator-facing error text kept in a log line.
    private static final int MAX_LOGGED_ERROR_LENGTH = 500;

    private final RevisionCheckRepository revisionCheckRepository;
    private final JobQueueService jobQueueService;
    private final ArticleRevisionService articleRevisionService;

    // The second listener on RevisionSubmittedEvent, beside ModerationService's, and plain
    // @EventListener for the same reason: it runs inside submit's transaction, so a revision
    // reaches PENDING with its check jobs queued or not at all. The worker reads the revision from
    // core's tables once the jobs commit, which is the same moment the PENDING text becomes
    // visible to it.
    @EventListener
    @Transactional
    public void onRevisionSubmitted(RevisionSubmittedEvent event) {
        for (RevisionCheckType type : RevisionCheckType.values()) {
            RevisionCheck check = revisionCheckRepository.findByRevisionIdAndType(event.revisionId(), type)
                    .orElseGet(() -> RevisionCheck.builder().revisionId(event.revisionId()).type(type).build());
            check.setStatus(RevisionCheckStatus.PENDING);
            check.setJobId(jobQueueService.enqueue(type.jobType(), Map.of("revision_id", event.revisionId().toString())));
            check.setReasonCode(null);
            check.setTotalCount(null);
            check.setProblemCount(null);
            check.setOmittedCount(null);
            check.setFindings(null);
            check.setFinishedAt(null);
            // A real submit owns the row from here on, even one a dev fixture minted first.
            check.setDevMarker(null);
            revisionCheckRepository.save(check);
        }
    }

    // jobStatus is the row's own lifecycle status ("done"/"failed"), resultJson its result
    // column. Throws only for infrastructure failures, which the caller retries on its next poll.
    @Transactional
    public void applyResult(UUID checkId, long jobId, String jobStatus, @Nullable String resultJson,
            @Nullable String lastError) {
        RevisionCheck check = revisionCheckRepository.findByIdForUpdate(checkId).orElse(null);
        if (check == null) {
            log.debug("ignoring job {}: revision check {} no longer exists", jobId, checkId);
            return;
        }
        if (check.getStatus() != RevisionCheckStatus.PENDING || !Long.valueOf(jobId).equals(check.getJobId())) {
            log.debug("ignoring job {}: revision check {} is {} awaiting job {}", jobId, check.getId(),
                    check.getStatus(), check.getJobId());
            return;
        }

        if (QueueJob.STATUS_FAILED.equals(jobStatus)) {
            log.warn("{} check of revision {} gave up (job {}): {}", check.getType(), check.getRevisionId(), jobId,
                    truncate(lastError));
            finish(check, RevisionCheckStatus.FAILED, null);
            return;
        }
        if (!QueueJob.STATUS_DONE.equals(jobStatus)) {
            log.error("job {} for revision check {} is {}, not finished", jobId, check.getId(), jobStatus);
            return;
        }

        JobResult result = jobQueueService.readResult(resultJson).orElse(null);
        Optional<String> problem = checkEnvelope(check, jobId, result);
        if (problem.isPresent()) {
            log.error("refusing job {} for {} check of revision {}: {}", jobId, check.getType(),
                    check.getRevisionId(), problem.get());
            finish(check, RevisionCheckStatus.FAILED, null);
            return;
        }

        if (JobResult.OUTCOME_REJECTED.equals(result.outcome())) {
            String code = result.reason() != null ? result.reason().code() : null;
            finish(check, RevisionCheckStatus.REJECTED, isCode(code) ? code : GENERIC_REJECTION);
            return;
        }
        Optional<String> findingsProblem = check.getType() == RevisionCheckType.NORMALIZE
                ? applyTextFindings(check, result.normalize())
                : applyLinkFindings(check, result.links());
        if (findingsProblem.isPresent()) {
            log.error("refusing job {} for {} check of revision {}: {}", jobId, check.getType(),
                    check.getRevisionId(), findingsProblem.get());
            finish(check, RevisionCheckStatus.FAILED, null);
            return;
        }
        finish(check, RevisionCheckStatus.DONE, null);
        log.info("{} check of revision {} done: {} of {} flagged", check.getType(), check.getRevisionId(),
                check.getProblemCount(), check.getTotalCount());
    }

    // For a caller who has already established the right to see the revision's checks (the
    // moderator-only task detail).
    @Transactional(readOnly = true)
    public RevisionChecksResponse getForRevision(UUID revisionId) {
        Map<RevisionCheckType, RevisionCheck> byType = new EnumMap<>(RevisionCheckType.class);
        revisionCheckRepository.findByRevisionId(revisionId).forEach(check -> byType.put(check.getType(), check));
        RevisionCheck text = byType.get(RevisionCheckType.NORMALIZE);
        RevisionCheck links = byType.get(RevisionCheckType.LINKS);
        return new RevisionChecksResponse(revisionId, text != null ? toTextResponse(text) : null,
                links != null ? toLinkResponse(links) : null);
    }

    // The author's (and any moderator's) own view; see ArticleRevisionService.requireChecksReadableBy
    // for who gets a 404 and who a 403.
    @Transactional(readOnly = true)
    public RevisionChecksResponse getForCaller(UUID callerUserId, boolean isCallerModerator, UUID articleId,
            String language, UUID revisionId) {
        UUID scopedRevisionId = articleRevisionService.requireChecksReadableBy(articleId, language, revisionId,
                callerUserId, isCallerModerator);
        return getForRevision(scopedRevisionId);
    }

    private static Optional<String> checkEnvelope(RevisionCheck check, long jobId, @Nullable JobResult result) {
        if (result == null) {
            return Optional.of("no readable result");
        }
        if (result.version() == null || result.version() != JobResult.CONTRACT_VERSION) {
            return Optional.of("unsupported result version " + result.version());
        }
        if (!Long.valueOf(jobId).equals(result.jobId())) {
            return Optional.of("result belongs to job " + result.jobId());
        }
        if (!check.getType().jobType().wireName().equals(result.type())) {
            return Optional.of("result type " + result.type());
        }
        if (!JobResult.OUTCOME_OK.equals(result.outcome()) && !JobResult.OUTCOME_REJECTED.equals(result.outcome())) {
            return Optional.of("unknown outcome " + result.outcome());
        }
        return Optional.empty();
    }

    // totalCount and problemCount are both every finding (each is a suggestion); omittedCount is
    // whatever the stored list leaves out, whether the worker or core cut it.
    private static Optional<String> applyTextFindings(RevisionCheck check, JobResult.@Nullable Normalize block) {
        if (block == null) {
            return Optional.of("ok result without a normalize block");
        }
        if (!check.getRevisionId().toString().equals(block.revisionId())) {
            return Optional.of("result is about revision " + block.revisionId());
        }
        List<TextFinding> findings = new ArrayList<>();
        for (JobResult.Finding finding : block.findings() != null ? block.findings() : List.<JobResult.Finding>of()) {
            if (findings.size() == MAX_TEXT_FINDINGS) {
                break;
            }
            if (finding != null && isCode(finding.rule())) {
                findings.add(new TextFinding(finding.rule(), nonNegative(finding.line()), nonNegative(finding.column()),
                        nonNegative(finding.offset()), cut(finding.found()), cut(finding.suggest()),
                        cut(finding.context())));
            }
        }
        int total = block.count() != null && block.count() >= findings.size() ? block.count() : findings.size();
        check.setTotalCount(total);
        check.setProblemCount(total);
        check.setOmittedCount(total - findings.size());
        check.setFindings(new RevisionCheckFindings(findings, List.of()));
        return Optional.empty();
    }

    // totalCount is the links checked, problemCount the broken or unreachable ones among those
    // stored (so it always matches what is listed), omittedCount the links the worker never got to.
    private static Optional<String> applyLinkFindings(RevisionCheck check, JobResult.@Nullable Links block) {
        if (block == null) {
            return Optional.of("ok result without a links block");
        }
        if (!check.getRevisionId().toString().equals(block.revisionId())) {
            return Optional.of("result is about revision " + block.revisionId());
        }
        List<LinkFinding> links = new ArrayList<>();
        for (JobResult.Link link : block.links() != null ? block.links() : List.<JobResult.Link>of()) {
            if (links.size() == MAX_LINKS) {
                break;
            }
            if (link != null && isWebUrl(link.url()) && isCode(link.status())) {
                links.add(new LinkFinding(link.url(), link.status(), httpStatus(link.httpStatus()),
                        isWebUrl(link.finalUrl()) ? link.finalUrl() : null,
                        isWebUrl(link.archiveUrl()) ? link.archiveUrl() : null,
                        isCode(link.error()) ? link.error() : null));
            }
        }
        check.setTotalCount(block.count() != null && block.count() >= links.size() ? block.count() : links.size());
        check.setProblemCount((int) links.stream().filter(link -> BROKEN_STATUSES.contains(link.status())).count());
        check.setOmittedCount(block.linksTruncated() != null && block.linksTruncated() > 0 ? block.linksTruncated() : 0);
        check.setFindings(new RevisionCheckFindings(List.of(), links));
        return Optional.empty();
    }

    // Leaves the counts and findings applyTextFindings/applyLinkFindings set for DONE; every other
    // outcome stores none.
    private void finish(RevisionCheck check, RevisionCheckStatus status, @Nullable String reasonCode) {
        check.setStatus(status);
        check.setReasonCode(reasonCode);
        check.setFinishedAt(Instant.now());
        if (status != RevisionCheckStatus.DONE) {
            check.setTotalCount(null);
            check.setProblemCount(null);
            check.setOmittedCount(null);
            check.setFindings(null);
        }
        revisionCheckRepository.save(check);
    }

    private static TextCheckResponse toTextResponse(RevisionCheck check) {
        List<TextFindingResponse> findings = check.getFindings() == null
                ? List.of()
                : check.getFindings().textFindings().stream()
                        .map(finding -> new TextFindingResponse(finding.rule(), finding.line(), finding.column(),
                                finding.offset(), finding.found(), finding.suggest(), finding.context()))
                        .toList();
        return new TextCheckResponse(check.getStatus(), check.getReasonCode(), check.getTotalCount(),
                check.getOmittedCount(), findings, check.getFinishedAt());
    }

    private static LinkCheckResponse toLinkResponse(RevisionCheck check) {
        List<LinkResultResponse> links = check.getFindings() == null
                ? List.of()
                : check.getFindings().links().stream()
                        .map(link -> new LinkResultResponse(link.url(), link.status(),
                                BROKEN_STATUSES.contains(link.status()), link.httpStatus(), link.finalUrl(),
                                link.archiveUrl(), link.error()))
                        .toList();
        return new LinkCheckResponse(check.getStatus(), check.getReasonCode(), check.getTotalCount(),
                check.getProblemCount(), check.getOmittedCount(), links, check.getFinishedAt());
    }

    private static boolean isCode(@Nullable String value) {
        return value != null && CODE.matcher(value).matches();
    }

    private static boolean isWebUrl(@Nullable String value) {
        if (value == null || value.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static @Nullable Integer nonNegative(@Nullable Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    private static @Nullable Integer httpStatus(@Nullable Integer value) {
        return value != null && value >= 100 && value <= 599 ? value : null;
    }

    // By code points, so a cut never splits a surrogate pair.
    private static @Nullable String cut(@Nullable String value) {
        if (value == null || value.codePointCount(0, value.length()) <= MAX_FINDING_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_FINDING_TEXT_LENGTH));
    }

    private static @Nullable String truncate(@Nullable String value) {
        return value == null || value.length() <= MAX_LOGGED_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_LOGGED_ERROR_LENGTH) + "...";
    }
}
