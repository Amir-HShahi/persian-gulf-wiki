package com.persiangulfwiki.core.user;

import com.persiangulfwiki.core.user.service.AvatarService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Deletes avatar reservations that were never completed, with whatever was uploaded for them.
// Scheduling is enabled globally by OAuth2CleanupConfig.
@Slf4j
@Component
@RequiredArgsConstructor
public class AvatarCleanupJob {

    private final AvatarService avatarService;

    @Value("${app.avatar.abandoned-upload-grace-hours}")
    private final long abandonedUploadGraceHours;

    // :02 hourly -- every 5-minute slot is taken by another hourly job.
    @Scheduled(cron = "0 2 * * * *")
    public void sweep() {
        int swept = avatarService.sweepAbandonedUploads(Instant.now().minus(abandonedUploadGraceHours, ChronoUnit.HOURS));
        if (swept > 0) {
            log.info("swept {} abandoned avatar uploads", swept);
        }
    }
}
