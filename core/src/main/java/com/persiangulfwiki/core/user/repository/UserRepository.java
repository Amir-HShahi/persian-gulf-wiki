package com.persiangulfwiki.core.user.repository;

import com.persiangulfwiki.core.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByGoogleSub(String googleSub);

    Optional<User> findByEmailAndPasswordHashIsNull(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    long deleteByPasswordHashIsNullAndCreatedAtBefore(Instant threshold);

    // Used only by DevTestUserSweeper, which exists only under the dev profile. Kept here
    // rather than on a dev-local repository so there is one JpaRepository per entity, matching
    // how deleteByPasswordHashIsNullAndCreatedAtBefore above serves OAuth2CleanupJob.
    long deleteByEmailStartingWithAndCreatedAtBefore(String emailPrefix, Instant threshold);
}
