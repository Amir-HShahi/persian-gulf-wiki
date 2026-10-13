package com.persiangulfwiki.core.user.repository;

import com.persiangulfwiki.core.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    // findByUsername binds the parameter as varchar, which makes Postgres compare as text and
    // loses citext's case-insensitivity; the cast keeps it.
    @Query(value = "select * from users where username = cast(:username as citext)", nativeQuery = true)
    Optional<User> findByUsernameIgnoringCase(@Param("username") String username);

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

    List<User> findByAvatarUploadExpiresAtBefore(Instant threshold);

    @Transactional
    @Modifying
    @Query("""
            update User u set u.avatarUploadId = null, u.avatarUploadContentType = null, u.avatarUploadBytes = null,
                u.avatarUploadSha256 = null, u.avatarUploadExpiresAt = null
            where u.id = :userId and u.avatarUploadId = :uploadId
            """)
    int clearAvatarUpload(@Param("userId") UUID userId, @Param("uploadId") UUID uploadId);

    // Every contribution of one user, one row each, on articles anyone can read (an approved
    // translation exists). Machine-generated revisions are nobody's contribution. A translation is
    // credited to the author of its first approved human revision, and the article's own language
    // is not a translation -- creating an article already counts as CREATED.
    String CONTRIBUTIONS = """
            with contributions as (
                select a.id as article_id, 'CREATED' as type, a.created_at as at
                from articles a
                where a.created_by_user_id = :userId
                union all
                select t.article_id, 'REVISION', r.created_at
                from article_revisions r
                join article_translations t on t.id = r.translation_id
                where r.author_id = :userId and r.status = 'APPROVED' and not r.is_machine_generated
                union all
                select t.article_id, 'TRANSLATION', first_revision.created_at
                from article_translations t
                join articles a on a.id = t.article_id
                join lateral (
                    select r.author_id, r.created_at
                    from article_revisions r
                    where r.translation_id = t.id and r.status = 'APPROVED' and not r.is_machine_generated
                    order by r.revision_number
                    limit 1
                ) first_revision on true
                where t.language <> a.canonical_language and first_revision.author_id = :userId
                union all
                select m.article_id, 'MEDIA', m.published_at
                from article_media m
                where m.uploaded_by = :userId and m.publication_status = 'PUBLISHED'
                  and m.processing_status = 'READY'
            ),
            public_contributions as (
                select c.* from contributions c
                where exists (select 1 from article_translations t
                              where t.article_id = c.article_id and t.current_revision_id is not null)
            )
            """;

    @Query(value = CONTRIBUTIONS + "select count(*) from public_contributions", nativeQuery = true)
    long countContributions(@Param("userId") UUID userId);

    @Query(value = CONTRIBUTIONS + """
            select article_id as articleId, string_agg(distinct type, ',') as types,
                   max(at) as lastContributedAt
            from public_contributions
            group by article_id
            order by max(at) desc, article_id
            limit :limit offset :offset
            """, nativeQuery = true)
    List<ContributedArticleRow> findContributedArticles(@Param("userId") UUID userId, @Param("limit") int limit,
            @Param("offset") long offset);

    @Query(value = CONTRIBUTIONS + "select count(distinct article_id) from public_contributions", nativeQuery = true)
    long countContributedArticles(@Param("userId") UUID userId);
}
