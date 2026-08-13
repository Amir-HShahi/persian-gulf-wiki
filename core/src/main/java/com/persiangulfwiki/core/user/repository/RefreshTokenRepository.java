package com.persiangulfwiki.core.user.repository;

import com.persiangulfwiki.core.user.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID userId);

    // Excludes expired-but-not-yet-revoked rows too — an expired row isn't a usable "active
    // session," even though nothing has explicitly revoked it yet.
    List<RefreshToken> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID userId, Instant now);

    // flushAutomatically pushes pending changes (e.g. a password-hash update earlier in the
    // same transaction) to the DB before this bulk update runs, since @Modifying bypasses
    // dirty checking; clearAutomatically avoids the persistence context returning stale
    // (pre-revocation) rows to callers that re-read after this bulk update.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken r set r.revokedAt = :revokedAt where r.user.id = :userId and r.revokedAt is null")
    int revokeAllActiveForUser(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);
}
