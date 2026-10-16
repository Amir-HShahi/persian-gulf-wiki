package com.persiangulfwiki.core.moderation;

import com.persiangulfwiki.core.moderation.dto.DailyCountResponse;
import com.persiangulfwiki.core.moderation.dto.DashboardRange;
import com.persiangulfwiki.core.moderation.dto.ModerationDashboardArticlesResponse;
import com.persiangulfwiki.core.moderation.dto.ModerationDashboardSummaryResponse;
import com.persiangulfwiki.core.moderation.exception.InvalidDashboardRangeException;
import com.persiangulfwiki.core.moderation.repository.DayCount;
import com.persiangulfwiki.core.moderation.repository.ModerationDecisionRepository;
import com.persiangulfwiki.core.moderation.repository.ModerationTaskRepository;
import com.persiangulfwiki.core.moderation.repository.StatusCount;
import com.persiangulfwiki.core.moderation.service.ModerationDashboardService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// The arithmetic half of the dashboard -- deltas, rounding, gap filling -- which the integration
// tests cannot pin down, because the shared test database makes yesterday's real counts unknowable.
class ModerationDashboardServiceTests {

    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");

    private ModerationTaskRepository moderationTaskRepository;
    private ModerationDecisionRepository moderationDecisionRepository;
    private ModerationDashboardService service;

    @BeforeEach
    void setUp() {
        moderationTaskRepository = mock(ModerationTaskRepository.class);
        moderationDecisionRepository = mock(ModerationDecisionRepository.class);
        service = new ModerationDashboardService(moderationTaskRepository, moderationDecisionRepository);
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(TEHRAN).toInstant();
    }

    private static StatusCount statusCount(String status, long count) {
        return new StatusCount() {
            @Override
            public String getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private static DayCount dayCount(LocalDate day, long count) {
        return new DayCount() {
            @Override
            public String getDay() {
                return day.toString();
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    @Test
    void summaryComparesTodayWithYesterdayAndOmitsTheChangeWhenYesterdayWasZero() {
        LocalDate today = LocalDate.now(TEHRAN);
        LocalDate yesterday = today.minusDays(1);
        when(moderationTaskRepository.countStillPendingArticlesSubmittedBetween(startOf(today), startOf(today.plusDays(1))))
                .thenReturn(56L);
        when(moderationTaskRepository.countStillPendingArticlesSubmittedBetween(startOf(yesterday), startOf(today)))
                .thenReturn(50L);
        when(moderationDecisionRepository.countArticleDecisionsBetween("APPROVE", startOf(today), startOf(today.plusDays(1))))
                .thenReturn(2L);
        when(moderationDecisionRepository.countArticleDecisionsBetween("APPROVE", startOf(yesterday), startOf(today)))
                .thenReturn(3L);
        when(moderationDecisionRepository.countArticleDecisionsBetween("REJECT", startOf(today), startOf(today.plusDays(1))))
                .thenReturn(4L);
        when(moderationDecisionRepository.countArticleDecisionsBetween("REJECT", startOf(yesterday), startOf(today)))
                .thenReturn(0L);

        ModerationDashboardSummaryResponse summary = service.summary();

        assertThat(summary.date()).isEqualTo(today);
        assertThat(summary.pending().count()).isEqualTo(56L);
        assertThat(summary.pending().changePercent()).isEqualTo(12.0);
        // 2 vs 3 is -33.33...%, rounded to one decimal.
        assertThat(summary.approved().changePercent()).isEqualTo(-33.3);
        assertThat(summary.rejected().count()).isEqualTo(4L);
        assertThat(summary.rejected().changePercent()).isNull();
    }

    @Test
    void articlesFillsEveryDayOfTheRangeEndingToday() {
        LocalDate today = LocalDate.now(TEHRAN);
        when(moderationDecisionRepository.countArticleApprovalsPerDayBetween(eq("Asia/Tehran"), any(), any()))
                .thenReturn(List.of(dayCount(today.minusDays(2), 5), dayCount(today, 7)));
        when(moderationTaskRepository.countArticlesByRevisionStatusSubmittedBetween(any(), any())).thenReturn(List.of());

        ModerationDashboardArticlesResponse articles = service.articles(null);

        assertThat(articles.range()).isEqualTo(DashboardRange.LAST_7_DAYS);
        assertThat(articles.from()).isEqualTo(today.minusDays(6));
        assertThat(articles.to()).isEqualTo(today);
        assertThat(articles.approvedPerDay()).extracting(DailyCountResponse::date)
                .containsExactlyElementsOf(today.minusDays(6).datesUntil(today.plusDays(1)).toList());
        assertThat(articles.approvedPerDay()).extracting(DailyCountResponse::count)
                .containsExactly(0L, 0L, 0L, 0L, 5L, 0L, 7L);
    }

    @Test
    void articlesAcceptsTheRangeInAnyCase() {
        when(moderationDecisionRepository.countArticleApprovalsPerDayBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(moderationTaskRepository.countArticlesByRevisionStatusSubmittedBetween(any(), any())).thenReturn(List.of());

        assertThat(service.articles("last_30_days").approvedPerDay()).hasSize(30);
    }

    @Test
    void articlesRejectsAnUnknownRange() {
        assertThatThrownBy(() -> service.articles("LAST_YEAR")).isInstanceOf(InvalidDashboardRangeException.class);
    }

    @Test
    void breakdownCountsChangesRequestedAsPendingAndPercentagesAddUpToExactly100() {
        when(moderationDecisionRepository.countArticleApprovalsPerDayBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        // 1/3 each would naively round to 33.3 + 33.3 + 33.3 = 99.9.
        when(moderationTaskRepository.countArticlesByRevisionStatusSubmittedBetween(any(), any())).thenReturn(List.of(
                statusCount("APPROVED", 1), statusCount("PENDING", 0), statusCount("CHANGES_REQUESTED", 1),
                statusCount("REJECTED", 1)));

        ModerationDashboardArticlesResponse articles = service.articles(null);

        assertThat(articles.statusBreakdown().pending().count()).isEqualTo(1L);
        assertThat(articles.statusBreakdown().approved().percent()
                + articles.statusBreakdown().pending().percent()
                + articles.statusBreakdown().rejected().percent()).isCloseTo(100.0, offset(1e-9));
    }

    @Test
    void breakdownIsAllZerosWhenNothingWasSubmitted() {
        when(moderationDecisionRepository.countArticleApprovalsPerDayBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(moderationTaskRepository.countArticlesByRevisionStatusSubmittedBetween(any(), any())).thenReturn(List.of());

        ModerationDashboardArticlesResponse articles = service.articles("LAST_7_DAYS");

        assertThat(articles.statusBreakdown().approved().percent()).isZero();
        assertThat(articles.statusBreakdown().pending().percent()).isZero();
        assertThat(articles.statusBreakdown().rejected().percent()).isZero();
    }
}
