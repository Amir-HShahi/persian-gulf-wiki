-- Phase 6: one main article per subject. A subject's page URL is its main article's slug, and its
-- listing card and 360° tour come from that article (decision 9). Until now the main article was
-- computed on every read as the subject's oldest article with an approved translation; this stores
-- the choice so a moderator can switch it (PATCH /api/subjects/{subjectId}/main-article).
--
-- Written by SubjectArticleService only: on an article's first approved translation when its
-- subject has no main yet, and on a moderator's switch. Both run under the subject row lock, so
-- uq_articles_main_per_subject is a backstop, not the concurrency control.
ALTER TABLE articles ADD COLUMN is_main BOOLEAN NOT NULL DEFAULT false;

-- A subject-less article has no subject to be the main article of.
ALTER TABLE articles
    ADD CONSTRAINT ck_articles_main_has_subject CHECK (NOT is_main OR subject_id IS NOT NULL);

-- Backfill with the rule phase 5 computed on read, so no subject's URL moves on deploy.
UPDATE articles
SET is_main = true
WHERE id IN (SELECT DISTINCT ON (a.subject_id) a.id
             FROM articles a
             WHERE a.subject_id IS NOT NULL
               AND EXISTS (SELECT 1 FROM article_translations t
                           WHERE t.article_id = a.id AND t.current_revision_id IS NOT NULL)
             ORDER BY a.subject_id, a.created_at, a.id);

CREATE UNIQUE INDEX uq_articles_main_per_subject ON articles (subject_id) WHERE is_main;
