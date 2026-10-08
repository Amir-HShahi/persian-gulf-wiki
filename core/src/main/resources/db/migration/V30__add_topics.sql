-- Topics: what an article is about (marine life, history, ...), as opposed to its subject's kind,
-- which is what a thing is. An article can have several. They are an article-level fact, the same
-- in every language, so they are attached to revisions of the article's canonical-language
-- translation only: the live set is the topics of that translation's current_revision_id, and a
-- translation inherits them. There is no article_topics copy to keep in sync -- approving a
-- revision is what makes its topics live.
--
-- Admins create and edit topics through the API; none are seeded. `slug` is a permanent code the
-- frontend keys on (same rule as attribute keys, V22); the displayed name lives in `labels`. `icon`
-- is a Lucide icon name and `color` a #RRGGBB hex, both editable: only their format is checked
-- here -- the frontend owns the icon set and falls back for a name it doesn't know. A topic is never
-- deleted, only archived: hidden from the home page and not newly assignable, while revisions
-- already tagged with it keep it.

CREATE TABLE topics (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        VARCHAR(60) NOT NULL,
    icon        VARCHAR(64) NOT NULL,
    -- Stored upper-case, so equal colors compare equal.
    color       VARCHAR(7) NOT NULL,
    sort_order  INTEGER NOT NULL,
    is_archived BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as subjects.dev_marker (V15).
    dev_marker  VARCHAR(40),
    CONSTRAINT uq_topics_slug UNIQUE (slug),
    CONSTRAINT ck_topics_slug CHECK (slug ~ '^[a-z][a-z0-9-]*$'),
    CONSTRAINT ck_topics_icon CHECK (icon ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_topics_color CHECK (color ~ '^#[0-9A-F]{6}$')
);

CREATE INDEX idx_topics_dev_marker ON topics (dev_marker) WHERE dev_marker IS NOT NULL;

-- Only revisions of a canonical-language translation have rows; ArticleRevisionService enforces
-- that, since a CHECK can't see the article's canonical_language. topic_id is RESTRICT: a topic
-- still tagging a revision is never deleted out from under it.
CREATE TABLE revision_topics (
    revision_id UUID NOT NULL REFERENCES article_revisions (id) ON DELETE CASCADE,
    topic_id    UUID NOT NULL REFERENCES topics (id),
    PRIMARY KEY (revision_id, topic_id)
);

CREATE INDEX idx_revision_topics_topic_id ON revision_topics (topic_id);

ALTER TABLE labels ADD COLUMN topic_id UUID REFERENCES topics (id) ON DELETE CASCADE;
ALTER TABLE labels DROP CONSTRAINT ck_labels_exactly_one_referent;
ALTER TABLE labels ADD CONSTRAINT ck_labels_exactly_one_referent
    CHECK (num_nonnulls(group_id, attribute_id, option_id, subject_id, kind, topic_id) = 1);
ALTER TABLE labels ADD CONSTRAINT uq_labels_topic_language UNIQUE (topic_id, language);
