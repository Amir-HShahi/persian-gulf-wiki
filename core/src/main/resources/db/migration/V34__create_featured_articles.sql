-- The home page's featured article: an admin picks an article for a time window. Several windows
-- may overlap; the one that started most recently wins. A window names the article, not a
-- translation -- the reader's language picks the translation, and a window whose article has no
-- approved translation in that language is skipped for that reader. With no window live (or
-- none usable in that language), the home page falls back to the newest published article, so
-- nothing here has to be maintained for the card to show something.
--
-- Rows are never edited: rescheduling is deleting a window and adding another.

CREATE TABLE featured_articles (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- CASCADE: a feature of an article that no longer exists has nothing left to show.
    article_id           UUID NOT NULL REFERENCES articles (id) ON DELETE CASCADE,
    starts_at            TIMESTAMPTZ NOT NULL,
    -- Null = open-ended; the window lasts until a later one starts or it is deleted.
    ends_at              TIMESTAMPTZ,
    -- SET NULL, same as articles.created_by_user_id: the schedule outlives the admin who set it.
    featured_by_user_id  UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as articles.dev_marker (V16).
    dev_marker           VARCHAR(40),
    CONSTRAINT ck_featured_articles_window CHECK (ends_at IS NULL OR ends_at > starts_at)
);

-- The home-page read: windows started by now, newest start first.
CREATE INDEX idx_featured_articles_starts_at ON featured_articles (starts_at DESC);
CREATE INDEX idx_featured_articles_article ON featured_articles (article_id);
CREATE INDEX idx_featured_articles_dev_marker ON featured_articles (dev_marker) WHERE dev_marker IS NOT NULL;
