package com.persiangulfwiki.core.marinedata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

@Entity
@Table(name = "marine_source_sync")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class MarineSourceSync {

    @EmbeddedId
    private MarineSourceSyncId id;

    // Drives the per-source interval: the next call for this pair is due one interval after
    // the last *attempt*, so a failing provider is retried at its normal cadence rather than
    // every tick.
    @Setter
    @Column(name = "last_attempt_at", nullable = false)
    private Instant lastAttemptAt;

    // Untouched by a failed attempt, which is what lets the card go stale while a provider is
    // down instead of claiming freshness it does not have.
    @Setter
    @Column(name = "last_success_at")
    private @Nullable Instant lastSuccessAt;

    @Setter
    @Column(name = "last_error", length = 500)
    private @Nullable String lastError;
}
