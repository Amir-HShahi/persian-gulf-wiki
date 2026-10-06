-- Topics: what an article is about (marine life, history, ...), as opposed to its subject's kind,
-- which is what a thing is. An article can have several. They are an article-level fact, the same
-- in every language, so they are attached to revisions of the article's canonical-language
-- translation only: the live set is the topics of that translation's current_revision_id, and a
-- translation inherits them. There is no article_topics copy to keep in sync -- approving a
-- revision is what makes its topics live.
--
-- `slug` and `icon` are immutable codes the frontend keys on (the icon names its icon set); the
-- displayed name lives in `labels`, like every other vocabulary entry (V22).

CREATE TABLE topics (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       VARCHAR(60) NOT NULL,
    icon       VARCHAR(40) NOT NULL,
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as subjects.dev_marker (V15).
    dev_marker VARCHAR(40),
    CONSTRAINT uq_topics_slug UNIQUE (slug),
    CONSTRAINT ck_topics_slug CHECK (slug ~ '^[a-z][a-z0-9-]*$')
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

-- The ten home-page categories, in the design's order.
INSERT INTO topics (slug, icon, sort_order) VALUES
    ('marine-life', 'fish', 1),
    ('plants-algae', 'leaf', 2),
    ('islands', 'pin', 3),
    ('ports-oil-terminals', 'anchor', 4),
    ('shipping-transit', 'ship', 5),
    ('pollution-environment', 'droplet', 6),
    ('history-archaeology', 'landmark', 7),
    ('economy-trade', 'chart', 8),
    ('geography-climate', 'compass', 9),
    ('culture-ethnography', 'users', 10);

INSERT INTO labels (topic_id, language, value)
SELECT t.id, v.language, v.value
FROM (VALUES
    ('marine-life', 'fa', 'زیست‌بوم و آبزیان'),
    ('marine-life', 'en', 'Marine life and ecosystems'),
    ('marine-life', 'ar', 'النظم البيئية والأحياء المائية'),
    ('plants-algae', 'fa', 'گیاهان و جلبک‌ها'),
    ('plants-algae', 'en', 'Plants and algae'),
    ('plants-algae', 'ar', 'النباتات والطحالب'),
    ('islands', 'fa', 'جزایر'),
    ('islands', 'en', 'Islands'),
    ('islands', 'ar', 'الجزر'),
    ('ports-oil-terminals', 'fa', 'بنادر و پایانه‌های نفتی'),
    ('ports-oil-terminals', 'en', 'Ports and oil terminals'),
    ('ports-oil-terminals', 'ar', 'الموانئ والمحطات النفطية'),
    ('shipping-transit', 'fa', 'دریانوردی و ترانزیت'),
    ('shipping-transit', 'en', 'Shipping and transit'),
    ('shipping-transit', 'ar', 'الملاحة والعبور'),
    ('pollution-environment', 'fa', 'آلودگی و محیط‌زیست'),
    ('pollution-environment', 'en', 'Pollution and environment'),
    ('pollution-environment', 'ar', 'التلوث والبيئة'),
    ('history-archaeology', 'fa', 'تاریخ و باستان‌شناسی'),
    ('history-archaeology', 'en', 'History and archaeology'),
    ('history-archaeology', 'ar', 'التاريخ والآثار'),
    ('economy-trade', 'fa', 'اقتصاد و تجارت'),
    ('economy-trade', 'en', 'Economy and trade'),
    ('economy-trade', 'ar', 'الاقتصاد والتجارة'),
    ('geography-climate', 'fa', 'جغرافیا و اقلیم'),
    ('geography-climate', 'en', 'Geography and climate'),
    ('geography-climate', 'ar', 'الجغرافيا والمناخ'),
    ('culture-ethnography', 'fa', 'فرهنگ و مردم‌نگاری'),
    ('culture-ethnography', 'en', 'Culture and ethnography'),
    ('culture-ethnography', 'ar', 'الثقافة والإثنوغرافيا')
) AS v (slug, language, value)
JOIN topics t ON t.slug = v.slug;

DO $$
DECLARE
    unlabeled BIGINT;
BEGIN
    SELECT count(*) INTO unlabeled FROM topics t
    WHERE (SELECT count(*) FROM labels l WHERE l.topic_id = t.id) <> 3;
    IF unlabeled > 0 THEN
        RAISE EXCEPTION 'V30: % topic(s) without exactly three labels', unlabeled;
    END IF;
END $$;
