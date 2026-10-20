package com.persiangulfwiki.core.marinedata.controller;

import com.persiangulfwiki.core.common.dto.ApiResult;
import com.persiangulfwiki.core.marinedata.dto.MarineMetricResponse;
import com.persiangulfwiki.core.marinedata.dto.MarineSeriesResponse;
import com.persiangulfwiki.core.marinedata.service.MarineMetricService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/marine-metrics")
@RequiredArgsConstructor
@Tag(name = "Marine Data", description = "Current Persian Gulf environmental readings for the homepage data cards: sea "
        + "surface temperature, coral bleaching stress and air quality, refreshed from public providers (Open-Meteo, "
        + "NOAA Coral Reef Watch). Values are raw numbers with stable metric, station and unit keys; labels, station "
        + "names, unit symbols and digit formatting are the client's job. Open to everyone; no account is required.")
public class MarineMetricController {

    private final MarineMetricService marineMetricService;
    private final MessageSource messageSource;

    @Operation(summary = "List the latest readings", description = "One entry per metric and station that has data, "
            + "each carrying everything a card needs: the latest value, its change against the previous 7 days, a "
            + "status band where the metric has one, and a 7-day sparkline. Decide staleness on the client by "
            + "comparing `staleAfter` with the current time.")
    @ApiResponse(responseCode = "200", description = "The cards. Empty before the first successful data refresh.")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<List<MarineMetricResponse>> latest() {
        List<MarineMetricResponse> data = marineMetricService.latest();
        String message = messageSource.getMessage("success.marineMetricsList", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }

    @Operation(summary = "Get a chart series", description = "Points for one metric at one station. DAY is 24 hourly "
            + "points, WEEK 7 daily and MONTH 30 daily, each window ending with the current hour or day. Daily-only "
            + "metrics (`sea_surface_temperature_anomaly`, `coral_bleaching_alert`, `degree_heating_weeks`) have no "
            + "DAY view.")
    @ApiResponse(responseCode = "200", description = "The series, oldest point first.")
    @ApiResponse(responseCode = "400", description = "`range` is not DAY, WEEK or MONTH, or DAY was asked for a "
            + "daily-only metric.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The metric key is unknown, or the station has no readings for "
            + "that metric.", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Parameter(name = "metricKey", description = "A metric key as returned by the list endpoint.", example = "sea_surface_temperature")
    @Parameter(name = "stationKey", description = "A station key as returned by the list endpoint.", example = "bushehr")
    @Parameter(name = "range", description = "DAY, WEEK or MONTH (case-insensitive). Defaults to WEEK.")
    @GetMapping("/{metricKey}/stations/{stationKey}/series")
    @ResponseStatus(HttpStatus.OK)
    public ApiResult<MarineSeriesResponse> series(
            @PathVariable String metricKey,
            @PathVariable String stationKey,
            @RequestParam(defaultValue = "WEEK") String range) {
        MarineSeriesResponse data = marineMetricService.series(metricKey, stationKey, range);
        String message = messageSource.getMessage("success.marineSeriesFetched", null, LocaleContextHolder.getLocale());
        return ApiResult.of(data, message);
    }
}
