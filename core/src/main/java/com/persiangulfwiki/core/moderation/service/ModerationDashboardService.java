package com.persiangulfwiki.core.moderation.service;

import com.persiangulfwiki.core.article.entity.RevisionStatus;
import com.persiangulfwiki.core.moderation.dto.DailyCountResponse;
import com.persiangulfwiki.core.moderation.dto.DashboardRange;
import com.persiangulfwiki.core.moderation.dto.DashboardStatResponse;
import com.persiangulfwiki.core.moderation.dto.ModerationDashboardArticlesResponse;
import com.persiangulfwiki.core.moderation.dto.ModerationDashboardSummaryResponse;
import com.persiangulfwiki.core.moderation.dto.StatusBreakdownResponse;
import com.persiangulfwiki.core.moderation.dto.StatusShareResponse;
import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.exception.InvalidDashboardRangeException;
import com.persiangulfwiki.core.moderation.repository.DayCount;
import com.persiangulfwiki.core.moderation.repository.ModerationDecisionRepository;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.moderation.repository.StatusCount;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// Every count here is over article revisions only; media and subject tasks share the queue but
// not the dashboard. Days are Tehran calendar days, whatever the server's own zone is.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModerationDashboardService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tehran");

    private final ModerationTaskRepository moderationTaskRepository;
    private final ModerationDecisionRepository moderationDecisionRepository;

    public ModerationDashboardSummaryResponse summary() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate yesterday = today.minusDays(1);
        return new ModerationDashboardSummaryResponse(today,
                stat(countStillPending(today), countStillPending(yesterday)),
                stat(countDecisions(Decision.APPROVE, today), countDecisions(Decision.APPROVE, yesterday)),
                stat(countDecisions(Decision.REJECT, today), countDecisions(Decision.REJECT, yesterday)));
    }

    /** {@code rangeFilter} is a case-insensitive {@link DashboardRange} name; null means LAST_7_DAYS. */
    public ModerationDashboardArticlesResponse articles(@Nullable String rangeFilter) {
        DashboardRange range = parseRange(rangeFilter);
        LocalDate to = LocalDate.now(ZONE);
        LocalDate from = to.minusDays(range.days() - 1L);
        Instant fromInstant = startOf(from);
        Instant toInstant = startOf(to.plusDays(1));

        Map<LocalDate, Long> approvalsByDay = moderationDecisionRepository
                .countArticleApprovalsPerDayBetween(ZONE.getId(), fromInstant, toInstant).stream()
                .collect(Collectors.toMap(row -> LocalDate.parse(row.getDay()), DayCount::getCount));
        List<DailyCountResponse> approvedPerDay = from.datesUntil(to.plusDays(1))
                .map(day -> new DailyCountResponse(day, approvalsByDay.getOrDefault(day, 0L)))
                .toList();

        return new ModerationDashboardArticlesResponse(range, from, to, approvedPerDay,
                breakdown(moderationTaskRepository.countArticlesByRevisionStatusSubmittedBetween(fromInstant,
                        toInstant)));
    }

    private long countStillPending(LocalDate day) {
        return moderationTaskRepository.countStillPendingArticlesSubmittedBetween(startOf(day),
                startOf(day.plusDays(1)));
    }

    private long countDecisions(Decision decision, LocalDate day) {
        return moderationDecisionRepository.countArticleDecisionsBetween(decision.name(), startOf(day),
                startOf(day.plusDays(1)));
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    private static DashboardStatResponse stat(long today, long yesterday) {
        Double changePercent = yesterday == 0
                ? null
                : Math.round((today - yesterday) * 1000.0 / yesterday) / 10.0;
        return new DashboardStatResponse(today, changePercent);
    }

    // A revision with a task was submitted at least once, so DRAFT should not occur; if one
    // does, it is left out of all three slices rather than miscounted as pending.
    private static StatusBreakdownResponse breakdown(List<StatusCount> rows) {
        long approved = 0;
        long pending = 0;
        long rejected = 0;
        for (StatusCount row : rows) {
            switch (RevisionStatus.valueOf(row.getStatus())) {
                case APPROVED -> approved += row.getCount();
                case PENDING, CHANGES_REQUESTED -> pending += row.getCount();
                case REJECTED -> rejected += row.getCount();
                case DRAFT -> { }
            }
        }
        double[] percents = percentsSummingTo100(new long[] {approved, pending, rejected});
        return new StatusBreakdownResponse(new StatusShareResponse(approved, percents[0]),
                new StatusShareResponse(pending, percents[1]), new StatusShareResponse(rejected, percents[2]));
    }

    // Largest-remainder rounding at one decimal, so the slices add up to exactly 100.0 instead
    // of 99.9 or 100.1. All zeros when there is nothing to divide.
    private static double[] percentsSummingTo100(long[] counts) {
        long total = 0;
        for (long count : counts) {
            total += count;
        }
        double[] percents = new double[counts.length];
        if (total == 0) {
            return percents;
        }
        long[] tenths = new long[counts.length];
        double[] remainders = new double[counts.length];
        long assigned = 0;
        for (int i = 0; i < counts.length; i++) {
            double exact = counts[i] * 1000.0 / total;
            tenths[i] = (long) Math.floor(exact);
            remainders[i] = exact - tenths[i];
            assigned += tenths[i];
        }
        List<Integer> byRemainder = IntStream.range(0, counts.length).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> remainders[i]).reversed())
                .toList();
        for (int k = 0; k < 1000 - assigned; k++) {
            tenths[byRemainder.get(k)]++;
        }
        for (int i = 0; i < counts.length; i++) {
            percents[i] = tenths[i] / 10.0;
        }
        return percents;
    }

    private static DashboardRange parseRange(@Nullable String rangeFilter) {
        if (rangeFilter == null) {
            return DashboardRange.LAST_7_DAYS;
        }
        try {
            return DashboardRange.valueOf(rangeFilter.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidDashboardRangeException();
        }
    }
}
