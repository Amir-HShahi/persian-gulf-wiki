-- The public profile. Every column is nullable: registration does not ask for any of it, and a
-- profile is shown publicly only once full_name and avatar_key are both set (UserProfileService).
ALTER TABLE users
    ADD COLUMN full_name                    VARCHAR(100),
    ADD COLUMN bio                          VARCHAR(500),
    ADD COLUMN affiliation                  VARCHAR(150),
    -- Key of the current avatar in the media bucket, under public/avatars/{userId}/.
    ADD COLUMN avatar_key                   TEXT,
    -- The one outstanding avatar reservation, if any. A new reservation replaces it; completing
    -- or abandoning it (AvatarCleanupJob) clears all five together.
    ADD COLUMN avatar_upload_id             UUID,
    ADD COLUMN avatar_upload_content_type   VARCHAR(100),
    ADD COLUMN avatar_upload_bytes          BIGINT,
    ADD COLUMN avatar_upload_sha256         VARCHAR(44),
    ADD COLUMN avatar_upload_expires_at     TIMESTAMPTZ,
    ADD CONSTRAINT ck_users_avatar_upload_complete
        CHECK ((avatar_upload_id IS NULL) = (avatar_upload_expires_at IS NULL)
            AND (avatar_upload_id IS NULL) = (avatar_upload_content_type IS NULL)
            AND (avatar_upload_id IS NULL) = (avatar_upload_bytes IS NULL)
            AND (avatar_upload_id IS NULL) = (avatar_upload_sha256 IS NULL));

CREATE INDEX idx_users_avatar_upload_expires_at ON users (avatar_upload_expires_at)
    WHERE avatar_upload_id IS NOT NULL;

CREATE TABLE user_profile_links (
    user_id   UUID     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    position  INT      NOT NULL,
    url       VARCHAR(500) NOT NULL,
    PRIMARY KEY (user_id, position)
);

-- The contribution queries (ContributionRepository) filter by author / creator.
CREATE INDEX idx_article_revisions_author ON article_revisions (author_id);
CREATE INDEX idx_articles_created_by ON articles (created_by_user_id);
