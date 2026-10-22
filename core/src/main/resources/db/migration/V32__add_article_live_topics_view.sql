-- An article's live topics (V30): those of its canonical-language translation's current, i.e. most
-- recently approved, revision; every translation inherits them. One definition, read by both the
-- home page's per-topic article counts and the article list's topic filter, so the two can't
-- disagree about which articles a topic has.
CREATE VIEW article_live_topics AS
SELECT a.id AS article_id, rt.topic_id
FROM articles a
JOIN article_translations canonical
  ON canonical.article_id = a.id AND canonical.language = a.canonical_language
JOIN revision_topics rt ON rt.revision_id = canonical.current_revision_id;
