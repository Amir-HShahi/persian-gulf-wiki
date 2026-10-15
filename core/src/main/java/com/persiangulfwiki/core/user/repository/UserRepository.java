package com.persiangulfwiki.core.user.repository;

import com.persiangulfwiki.core.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
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

    // Every contribution of the users in :userIds (a single UUID binds too), one row each, on
    // articles anyone can read (an approved translation exists). Machine-generated revisions are
    // nobody's contribution. A translation is credited to the author of its first approved human
    // revision, and the article's own language is not a translation -- creating an article
    // already counts as CREATED. The public profile and the admin user list both count from
    // here, so the two numbers can't drift apart.
    String CONTRIBUTIONS = """
            with contributions as (
                select a.created_by_user_id as user_id, a.id as article_id, 'CREATED' as type, a.created_at as at
                from articles a
                where a.created_by_user_id in (:userIds)
                union all
                select r.author_id, t.article_id, 'REVISION', r.created_at
                from article_revisions r
                join article_translations t on t.id = r.translation_id
                where r.author_id in (:userIds) and r.status = 'APPROVED' and not r.is_machine_generated
                union all
                select first_revision.author_id, t.article_id, 'TRANSLATION', first_revision.created_at
                from article_translations t
                join articles a on a.id = t.article_id
                join lateral (
                    select r.author_id, r.created_at
                    from article_revisions r
                    where r.translation_id = t.id and r.status = 'APPROVED' and not r.is_machine_generated
                    order by r.revision_number
                    limit 1
                ) first_revision on true
                where t.language <> a.canonical_language and first_revision.author_id in (:userIds)
                union all
                select m.uploaded_by, m.article_id, 'MEDIA', m.published_at
                from article_media m
                where m.uploaded_by in (:userIds) and m.publication_status = 'PUBLISHED'
                  and m.processing_status = 'READY'
            ),
            public_contributions as (
                select c.* from contributions c
                where exists (select 1 from article_translations t
                              where t.article_id = c.article_id and t.current_revision_id is not null)
            )
            """;

    @Query(value = CONTRIBUTIONS + "select count(*) from public_contributions", nativeQuery = true)
    long countContributions(@Param("userIds") UUID userId);

    @Query(value = CONTRIBUTIONS + """
            select article_id as articleId, string_agg(distinct type, ',') as types,
                   max(at) as lastContributedAt
            from public_contributions
            group by article_id
            order by max(at) desc, article_id
            limit :limit offset :offset
            """, nativeQuery = true)
    List<ContributedArticleRow> findContributedArticles(@Param("userIds") UUID userId, @Param("limit") int limit,
            @Param("offset") long offset);

    @Query(value = CONTRIBUTIONS + "select count(distinct article_id) from public_contributions", nativeQuery = true)
    long countContributedArticles(@Param("userIds") UUID userId);

    // Users with no public contribution have no row.
    @Query(value = CONTRIBUTIONS + """
            select user_id as userId, count(*) as contributionCount
            from public_contributions
            group by user_id
            """, nativeQuery = true)
    List<UserContributionCountRow> countContributionsByUser(@Param("userIds") Collection<UUID> userIds);

    // The admin user list's filter: role flags OR together (contributorOnly = holds no role
    // besides CONTRIBUTOR; anyRole false = no role filter), then the enabled filter ANDs on, then
    // the search: :query must be a substring of the full name, username or email, each folded by
    // subject_search_key (V29) on its own so a match can't span two fields. A query that folds to
    // empty (only punctuation or spacing) matches nobody rather than everybody.
    String ADMIN_USER_FILTER = """
            from users u
            where (not :filterEnabled or u.enabled = :enabled)
              and (not :anyRole
                   or (:admin and exists (select 1 from user_roles r where r.user_id = u.id and r.role = 'ADMIN'))
                   or (:moderator and exists (select 1 from user_roles r where r.user_id = u.id and r.role = 'MODERATOR'))
                   or (:expertReviewer and exists (select 1 from user_roles r
                                                   where r.user_id = u.id and r.role = 'EXPERT_REVIEWER'))
                   or (:contributorOnly and not exists (select 1 from user_roles r
                                                        where r.user_id = u.id and r.role <> 'CONTRIBUTOR')))
              and (not :filterQuery
                   or (subject_search_key(:query) <> ''
                       and (strpos(subject_search_key(u.full_name), subject_search_key(:query)) > 0
                            or strpos(subject_search_key(u.username), subject_search_key(:query)) > 0
                            or strpos(subject_search_key(u.email), subject_search_key(:query)) > 0)))
            """;

    @Query(value = "select u.* " + ADMIN_USER_FILTER + " order by u.created_at desc, u.id limit :limit offset :offset",
            nativeQuery = true)
    List<User> findForAdmin(@Param("filterEnabled") boolean filterEnabled, @Param("enabled") boolean enabled,
            @Param("anyRole") boolean anyRole, @Param("admin") boolean admin, @Param("moderator") boolean moderator,
            @Param("expertReviewer") boolean expertReviewer, @Param("contributorOnly") boolean contributorOnly,
            @Param("filterQuery") boolean filterQuery, @Param("query") String query, @Param("limit") int limit,
            @Param("offset") long offset);

    @Query(value = "select count(*) " + ADMIN_USER_FILTER, nativeQuery = true)
    long countForAdmin(@Param("filterEnabled") boolean filterEnabled, @Param("enabled") boolean enabled,
            @Param("anyRole") boolean anyRole, @Param("admin") boolean admin, @Param("moderator") boolean moderator,
            @Param("expertReviewer") boolean expertReviewer, @Param("contributorOnly") boolean contributorOnly,
            @Param("filterQuery") boolean filterQuery, @Param("query") String query);

    // The admin list's chip counts, over every user regardless of the active filter.
    @Query(value = """
            select count(*) as total,
                   count(*) filter (where not u.enabled) as blocked,
                   count(*) filter (where exists (select 1 from user_roles r
                                                  where r.user_id = u.id and r.role = 'ADMIN')) as admin,
                   count(*) filter (where exists (select 1 from user_roles r
                                                  where r.user_id = u.id and r.role = 'MODERATOR')) as moderator,
                   count(*) filter (where exists (select 1 from user_roles r
                                                  where r.user_id = u.id and r.role = 'EXPERT_REVIEWER')) as expertReviewer,
                   count(*) filter (where not exists (select 1 from user_roles r
                                                      where r.user_id = u.id and r.role <> 'CONTRIBUTOR')) as contributor
            from users u
            """, nativeQuery = true)
    AdminUserCountsRow countUsersForAdmin();
}
