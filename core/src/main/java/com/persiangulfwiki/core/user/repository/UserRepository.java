package com.persiangulfwiki.core.user.repository;

import com.persiangulfwiki.core.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // Used only by the dev demo-content wipe. Skips any account still named by a row its
    // NO ACTION foreign keys protect (authored, uploaded, verified or moderated something that
    // outlived the wipe), so content someone made while logged in as a demo user survives instead
    // of failing the whole statement.
    @Modifying
    @Query(value = """
            delete from users u
            where u.email like :emailPrefix || '%' || :emailDomain
              and not exists (select 1 from article_revisions r where r.author_id = u.id)
              and not exists (select 1 from subject_revisions r where r.author_id = u.id)
              and not exists (select 1 from article_media m where m.uploaded_by = u.id)
              and not exists (select 1 from media_metadata_versions v where v.submitted_by = u.id)
              and not exists (select 1 from expert_verifications e where e.expert_id = u.id)
              and not exists (select 1 from moderation_decisions d where d.moderator_id = u.id)
            """, nativeQuery = true)
    int deleteUnreferencedByEmailPrefixAndDomain(@Param("emailPrefix") String emailPrefix,
            @Param("emailDomain") String emailDomain);

    long countByEmailStartingWith(String emailPrefix);
}
