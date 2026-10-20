-- Seeds the home page's starting topics in fa/en/ar. This supersedes V30's "none are seeded";
-- V30 is left untouched so its checksum stays valid where it has already run. Like V23, every row
-- is an ordinary admin-editable one: not dev-marked, so the dev routes and sweeper never touch it.
--
-- An environment may already have a topic under one of these slugs, made through the admin API.
-- That topic wins: it is skipped, labels included, rather than failing the migration on
-- uq_topics_slug. Icons are Lucide names; the one color is the design's tile accent.

WITH seed (slug, icon, sort_order) AS (
    VALUES
        ('marine-life', 'fish', 10),
        ('plants-and-algae', 'leaf', 20),
        ('islands', 'map-pin', 30),
        ('ports-and-oil-terminals', 'anchor', 40),
        ('shipping-and-transit', 'ship', 50),
        ('pollution-and-environment', 'droplet', 60),
        ('history-and-archaeology', 'landmark', 70),
        ('economy-and-trade', 'chart-no-axes-column', 80),
        ('geography-and-climate', 'compass', 90),
        ('culture-and-ethnography', 'users', 100)
),
inserted AS (
    INSERT INTO topics (slug, icon, color, sort_order)
    SELECT slug, icon, '#1288B6', sort_order FROM seed
    ON CONFLICT (slug) DO NOTHING
    RETURNING id, slug
)
INSERT INTO labels (topic_id, language, value)
SELECT i.id, v.language, v.value
FROM (VALUES
    ('marine-life', 'fa', 'زیست‌بوم و آبزیان'),
    ('marine-life', 'en', 'Marine life'),
    ('marine-life', 'ar', 'الحياة البحرية'),
    ('plants-and-algae', 'fa', 'گیاهان و جلبک‌ها'),
    ('plants-and-algae', 'en', 'Plants and algae'),
    ('plants-and-algae', 'ar', 'النباتات والطحالب'),
    ('islands', 'fa', 'جزایر'),
    ('islands', 'en', 'Islands'),
    ('islands', 'ar', 'الجزر'),
    ('ports-and-oil-terminals', 'fa', 'بنادر و پایانه‌های نفتی'),
    ('ports-and-oil-terminals', 'en', 'Ports and oil terminals'),
    ('ports-and-oil-terminals', 'ar', 'الموانئ والمحطات النفطية'),
    ('shipping-and-transit', 'fa', 'دریانوردی و ترانزیت'),
    ('shipping-and-transit', 'en', 'Shipping and transit'),
    ('shipping-and-transit', 'ar', 'الملاحة والعبور'),
    ('pollution-and-environment', 'fa', 'آلودگی و محیط‌زیست'),
    ('pollution-and-environment', 'en', 'Pollution and environment'),
    ('pollution-and-environment', 'ar', 'التلوث والبيئة'),
    ('history-and-archaeology', 'fa', 'تاریخ و باستان‌شناسی'),
    ('history-and-archaeology', 'en', 'History and archaeology'),
    ('history-and-archaeology', 'ar', 'التاريخ وعلم الآثار'),
    ('economy-and-trade', 'fa', 'اقتصاد و تجارت'),
    ('economy-and-trade', 'en', 'Economy and trade'),
    ('economy-and-trade', 'ar', 'الاقتصاد والتجارة'),
    ('geography-and-climate', 'fa', 'جغرافیا و اقلیم'),
    ('geography-and-climate', 'en', 'Geography and climate'),
    ('geography-and-climate', 'ar', 'الجغرافيا والمناخ'),
    ('culture-and-ethnography', 'fa', 'فرهنگ و مردم‌نگاری'),
    ('culture-and-ethnography', 'en', 'Culture and ethnography'),
    ('culture-and-ethnography', 'ar', 'الثقافة والإثنوغرافيا')
) AS v (slug, language, value)
JOIN inserted i ON i.slug = v.slug;

-- Sanity check ---------------------------------------------------------------------------------

-- Every seeded slug must exist with its canonical Farsi label (a pre-existing admin-made topic has
-- one too, since the API requires it); a miss means a mistyped slug above silently dropped rows.
DO $$
DECLARE
    found BIGINT;
BEGIN
    SELECT count(*) INTO found
    FROM topics t
    JOIN labels l ON l.topic_id = t.id AND l.language = 'fa'
    WHERE t.slug IN ('marine-life', 'plants-and-algae', 'islands', 'ports-and-oil-terminals',
                     'shipping-and-transit', 'pollution-and-environment', 'history-and-archaeology',
                     'economy-and-trade', 'geography-and-climate', 'culture-and-ethnography');
    IF found <> 10 THEN
        RAISE EXCEPTION 'V31: expected 10 seeded topics with a Farsi label, found %', found;
    END IF;
END $$;
