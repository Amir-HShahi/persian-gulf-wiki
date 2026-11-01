package com.persiangulfwiki.core.moderation.dto;

import java.time.LocalDate;

public record DailyCountResponse(LocalDate date, long count) {
}
