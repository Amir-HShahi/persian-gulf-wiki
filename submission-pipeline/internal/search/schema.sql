-- Article search.
--
-- Like every schema object this belongs in core's Flyway migrations: the worker
-- never runs DDL. The worker calls search_reconcile() to keep search_documents
-- in step with what the site publishes, and core calls search_articles() to
-- answer a query.
--
-- Core's tables are only ever read. There are no triggers and no foreign keys
-- into core's schema, so nothing in here can fail a write on the main site: if
-- search breaks, the index falls behind and the site carries on.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- search_fold returns the match-only form of a text: what is compared, never
-- what is shown. Both sides of every comparison go through it, at index time
-- and at query time, so a reader typing ماهی finds an article spelled ماهي.
--
--   * NFKC turns Arabic presentation forms and compatibility characters into
--     plain letters: ﻻ becomes لا, ﮐ becomes ک, full-width Latin becomes Latin.
--   * Tatweel, harakat, Quranic marks, superscript alef, ZWNJ and ZWJ, the soft
--     hyphen, the BOM and bidi controls are dropped, so کتاب‌ها, کتابها, کتـاب
--     and کِتاب all meet.
--   * Arabic letter forms fold to the ones a Persian keyboard types, and hamza
--     carriers and alef variants fold to the bare letter.
--   * Persian and Arabic-Indic digits fold to ASCII, so ۱۴۰۲ finds 1402.
--   * Case is folded and whitespace collapses to single spaces.
--
-- Stored rows are not rewritten when this changes. After editing it, run
-- TRUNCATE search_documents and let the worker rebuild the index.
CREATE OR REPLACE FUNCTION search_fold(input text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT btrim(regexp_replace(
        lower(translate(
            regexp_replace(
                normalize(input, NFKC),
                '[\u0640\u064B-\u065F\u0670\u06D6-\u06ED\u00AD\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]',
                '', 'g'),
            'كيىأإآٱةۀؤئ' || '٠١٢٣٤٥٦٧٨٩' || '۰۱۲۳۴۵۶۷۸۹' || '،؟؛',
            'کییااااههوی' || '0123456789' || '0123456789' || ',?;')),
        '\s+', ' ', 'g'))
$$;

-- search_body_text pulls the readable text out of an article body. Core keeps
-- bodies as free-form JSON from the editor with no fixed schema, so this does
-- not assume one: it walks every object at any depth, in document order, and
-- keeps the string values of the keys that carry prose. Structural keys such as
-- type, href, src and id are never read, so node names and URLs do not become
-- search terms.
CREATE OR REPLACE FUNCTION search_body_text(body jsonb) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT coalesce(string_agg(part, E'\n' ORDER BY ord, k), '')
    FROM (
        -- A body that is nothing but a string.
        SELECT 0::bigint AS ord, 0 AS k, body #>> '{}' AS part
         WHERE jsonb_typeof(body) = 'string'
        UNION ALL
        -- strict, not lax: in lax mode .** visits every array element twice.
        SELECT n.ord, f.k, n.node ->> f.key
          FROM jsonb_path_query(body, 'strict $.**') WITH ORDINALITY AS n(node, ord)
         CROSS JOIN (VALUES (1, 'title'), (2, 'text'), (3, 'caption'), (4, 'alt')) AS f(k, key)
         WHERE jsonb_typeof(n.node -> f.key) = 'string'
    ) parts
    WHERE btrim(part) <> ''
$$;

-- search_word_key is the folded form of one word as written in the text, with
-- the punctuation around it removed, so (هرمز، and Hormuz, both still match a
-- query by their first letters.
CREATE OR REPLACE FUNCTION search_word_key(word text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT regexp_replace(search_fold(word), '^[[:punct:]«»“”‘’]+|[[:punct:]«»“”‘’]+$', '', 'g')
$$;

CREATE OR REPLACE FUNCTION search_html_escape(input text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT replace(replace(replace(replace(replace(input,
        '&', '&amp;'), '<', '&lt;'), '>', '&gt;'), '"', '&quot;'), '''', '&#39;')
$$;

-- search_snippet cuts a passage around the first match and marks every word
-- that matches.
--
-- Matching happens on the folded keys and display uses the words exactly as
-- written, so a reader sees آبراه and Kish rather than the folded ابراه and
-- kish. The two arrays are built side by side at index time, one entry per
-- word, which is what keeps them aligned.
--
-- Every word is HTML-escaped before it is marked, so the result is safe to
-- render as HTML.
CREATE OR REPLACE FUNCTION search_snippet(words text[], keys text[], terms text[], window_words integer DEFAULT 30)
    RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    WITH w AS (
        SELECT n AS pos, word,
               EXISTS (SELECT 1 FROM unnest(terms) AS term
                        WHERE term <> '' AND starts_with(keys[n], term)) AS hit
          FROM unnest(words) WITH ORDINALITY AS u(word, n)
    ),
    span AS (
        -- Start a third of the way before the first match, so it reads with
        -- some context instead of opening on the matched word.
        SELECT greatest(coalesce(min(pos) FILTER (WHERE hit), 1) - window_words / 3, 1) AS lo,
               max(pos) AS last
          FROM w
    )
    SELECT CASE WHEN span.lo > 1 THEN '… ' ELSE '' END
        || string_agg(CASE WHEN w.hit THEN '<mark>' || search_html_escape(w.word) || '</mark>'
                           ELSE search_html_escape(w.word) END, ' ' ORDER BY w.pos)
        || CASE WHEN span.lo + window_words - 1 < span.last THEN ' …' ELSE '' END
      FROM w, span
     WHERE w.pos BETWEEN span.lo AND span.lo + window_words - 1
     GROUP BY span.lo, span.last
$$;

-- One row per published translation: exactly what the site lists, which is a
-- translation whose current_revision_id is set. Core sets that only when a
-- revision is approved, and approved revisions are never edited in place.
CREATE TABLE IF NOT EXISTS search_documents (
    translation_id uuid         PRIMARY KEY,
    article_id     uuid         NOT NULL,
    revision_id    uuid         NOT NULL,
    language       varchar(20)  NOT NULL,
    entity_type    varchar(20)  NOT NULL,
    slug           varchar(200) NOT NULL,
    title          text         NOT NULL,
    summary        text         NOT NULL DEFAULT '',
    body_text      text         NOT NULL DEFAULT '',
    indexed_at     timestamptz  NOT NULL DEFAULT now(),

    -- The summary and body split into words as written, and the same words
    -- folded, entry for entry. Snippets match on the second and show the first.
    excerpt_words  text[]       NOT NULL DEFAULT '{}',
    excerpt_keys   text[]       NOT NULL DEFAULT '{}',

    -- The folded title the typo matching and title boosts compare against.
    title_key      text GENERATED ALWAYS AS (search_fold(title)) STORED,

    -- Title outweighs slug and summary, which outweigh the body. English
    -- documents also carry stemmed lexemes, so islands finds island. Persian
    -- and Arabic have no stemmer in Postgres; prefix matching covers most of
    -- what one would, since کتاب then finds کتابها.
    document       tsvector GENERATED ALWAYS AS (
           setweight(to_tsvector('simple', search_fold(title)), 'A')
        || setweight(to_tsvector('simple', search_fold(translate(slug, '-_', '  '))), 'B')
        || setweight(to_tsvector('simple', search_fold(summary)), 'B')
        || setweight(to_tsvector('simple', search_fold(body_text)), 'C')
        || CASE WHEN language = 'en' OR language LIKE 'en-%' THEN
                   setweight(to_tsvector('english', search_fold(title)), 'A')
                || setweight(to_tsvector('english', search_fold(summary)), 'B')
                || setweight(to_tsvector('english', search_fold(body_text)), 'C')
           ELSE ''::tsvector END
    ) STORED
);

CREATE INDEX IF NOT EXISTS idx_search_documents_document
    ON search_documents USING gin (document);
CREATE INDEX IF NOT EXISTS idx_search_documents_title_trgm
    ON search_documents USING gin (title_key gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_search_documents_filters
    ON search_documents (language, entity_type);

-- search_refresh brings one translation's row in line with core and reports
-- 'indexed', 'removed' or 'unchanged'. It reads the current state rather than
-- applying a change, so calling it twice, late or out of order is harmless.
CREATE OR REPLACE FUNCTION search_refresh(p_translation uuid) RETURNS text
    LANGUAGE plpgsql
AS $$
DECLARE
    src       record;
    v_body    text;
    v_words   text[];
    v_keys    text[];
BEGIN
    SELECT t.id, t.article_id, t.language, t.slug, a.entity_type,
           r.id AS revision_id, r.title, coalesce(r.summary, '') AS summary, r.body
      INTO src
      FROM article_translations t
      JOIN articles a ON a.id = t.article_id
      JOIN article_revisions r ON r.id = t.current_revision_id
     WHERE t.id = p_translation;

    IF NOT FOUND THEN
        DELETE FROM search_documents WHERE translation_id = p_translation;
        IF FOUND THEN
            RETURN 'removed';
        END IF;
        RETURN 'unchanged';
    END IF;

    v_body := search_body_text(src.body);

    -- Split once into words as written, and fold each word on its own, so the
    -- two arrays stay aligned however much folding changes a word.
    SELECT coalesce(array_agg(w ORDER BY n), '{}'),
           coalesce(array_agg(search_word_key(w) ORDER BY n), '{}')
      INTO v_words, v_keys
      FROM regexp_split_to_table(btrim(src.summary || E'\n' || v_body), '\s+') WITH ORDINALITY AS s(w, n)
     WHERE w <> '';

    INSERT INTO search_documents AS d
           (translation_id, article_id, revision_id, language, entity_type,
            slug, title, summary, body_text, excerpt_words, excerpt_keys, indexed_at)
    VALUES (src.id, src.article_id, src.revision_id, src.language, src.entity_type,
            src.slug, src.title, src.summary, v_body, v_words, v_keys, now())
    ON CONFLICT (translation_id) DO UPDATE
       SET article_id    = EXCLUDED.article_id,
           revision_id   = EXCLUDED.revision_id,
           language      = EXCLUDED.language,
           entity_type   = EXCLUDED.entity_type,
           slug          = EXCLUDED.slug,
           title         = EXCLUDED.title,
           summary       = EXCLUDED.summary,
           body_text     = EXCLUDED.body_text,
           excerpt_words = EXCLUDED.excerpt_words,
           excerpt_keys  = EXCLUDED.excerpt_keys,
           indexed_at    = now()
     WHERE (d.revision_id, d.slug, d.entity_type, d.article_id, d.language)
           IS DISTINCT FROM
           (EXCLUDED.revision_id, EXCLUDED.slug, EXCLUDED.entity_type, EXCLUDED.article_id, EXCLUDED.language);

    IF FOUND THEN
        RETURN 'indexed';
    END IF;
    RETURN 'unchanged';
END
$$;

-- search_reconcile finds up to p_limit translations whose row is missing,
-- stale or no longer published, refreshes them, and reports what it did.
-- Because the current revision only changes on approval and approved revisions
-- are immutable, comparing revision ids is an exact change test: nothing has to
-- re-read a body to find out whether it moved.
CREATE OR REPLACE FUNCTION search_reconcile(p_limit integer DEFAULT 500)
    RETURNS TABLE (indexed integer, removed integer)
    LANGUAGE plpgsql
AS $$
DECLARE
    v_id      uuid;
    v_outcome text;
BEGIN
    indexed := 0;
    removed := 0;

    -- One reconciler at a time across every worker replica. A second caller
    -- returns immediately instead of repeating the same work.
    IF NOT pg_try_advisory_xact_lock(hashtext('search_reconcile')) THEN
        RETURN NEXT;
        RETURN;
    END IF;

    FOR v_id IN
        SELECT t.id
          FROM article_translations t
          JOIN articles a ON a.id = t.article_id
          LEFT JOIN search_documents d ON d.translation_id = t.id
         WHERE t.current_revision_id IS NOT NULL
           AND (d.translation_id IS NULL
                OR d.revision_id <> t.current_revision_id
                OR d.slug <> t.slug
                OR d.entity_type <> a.entity_type
                OR d.article_id <> t.article_id)
        UNION ALL
        SELECT d.translation_id
          FROM search_documents d
          LEFT JOIN article_translations t ON t.id = d.translation_id
         WHERE t.id IS NULL OR t.current_revision_id IS NULL
         LIMIT greatest(p_limit, 1)
    LOOP
        v_outcome := search_refresh(v_id);
        IF v_outcome = 'indexed' THEN
            indexed := indexed + 1;
        ELSIF v_outcome = 'removed' THEN
            removed := removed + 1;
        END IF;
    END LOOP;

    RETURN NEXT;
END
$$;

-- search_articles answers one query. Language and entity type are optional
-- filters. Results are ranked, paged, and carry the total count on every row.
--
-- The snippet is HTML: the excerpt is escaped first and only then wrapped in
-- <mark>, so it is safe to render as-is. Every other column is plain text and
-- must be escaped by whoever displays it.
CREATE OR REPLACE FUNCTION search_articles(
        p_query       text,
        p_language    text    DEFAULT NULL,
        p_entity_type text    DEFAULT NULL,
        p_limit       integer DEFAULT 20,
        p_offset      integer DEFAULT 0)
    RETURNS TABLE (
        translation_id uuid,
        article_id     uuid,
        language       varchar,
        entity_type    varchar,
        slug           varchar,
        title          text,
        snippet        text,
        score          real,
        total          bigint)
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$
    WITH q AS (
        SELECT search_fold(coalesce(p_query, '')) AS key
    ),
    tq AS (
        SELECT q.key,
               -- Every word as a prefix, all of them required, so a query finds
               -- its article while it is still being typed.
               (SELECT to_tsquery('simple', string_agg(quote_literal(replace(l, '\', '')) || ':*', ' & '))
                  FROM unnest(tsvector_to_array(to_tsvector('simple', q.key))) AS l) AS words,
               -- English stems. Only English documents carry stemmed lexemes.
               plainto_tsquery('english', q.key) AS stems,
               -- What a snippet highlights: each query word, plus its English
               -- stem so that a search for islands also marks island.
               ARRAY(SELECT unnest(tsvector_to_array(to_tsvector('simple', q.key)))
                     UNION
                     SELECT unnest(tsvector_to_array(to_tsvector('english', q.key)))) AS terms
          FROM q
    ),
    candidates AS (
        SELECT d.translation_id, d.article_id, d.language, d.entity_type, d.slug, d.title,
               d.title_key, d.excerpt_words, d.excerpt_keys, d.document, d.indexed_at,
               tq.key, tq.words, tq.stems, tq.terms
          FROM search_documents d
         CROSS JOIN tq
         WHERE (p_language IS NULL OR d.language = p_language)
           AND (p_entity_type IS NULL OR d.entity_type = p_entity_type)
    ),
    exact AS (
        SELECT c.*
          FROM candidates c
         WHERE (c.words IS NOT NULL AND c.document @@ c.words)
            OR (numnode(c.stems) > 0 AND c.document @@ c.stems)
    ),
    fuzzy AS (
        -- Typo tolerance on titles, used only when nothing matched exactly: a
        -- misspelt query should still land, and a correct one should not be
        -- diluted with near misses.
        SELECT c.*
          FROM candidates c
         WHERE NOT EXISTS (SELECT 1 FROM exact)
           AND c.key <> ''
           AND (c.title_key % c.key OR c.key <% c.title_key)
    ),
    scored AS (
        SELECT h.*,
               (  coalesce(ts_rank_cd(h.document, h.words, 32), 0)
                + CASE WHEN numnode(h.stems) > 0 THEN ts_rank_cd(h.document, h.stems, 32) ELSE 0 END
                + CASE WHEN h.title_key = h.key THEN 1.0
                       WHEN starts_with(h.title_key, h.key) THEN 0.5
                       ELSE 0 END
                + 0.5 * similarity(h.title_key, h.key)
               )::real AS score
          FROM (SELECT * FROM exact UNION ALL SELECT * FROM fuzzy) h
    )
    SELECT s.translation_id, s.article_id, s.language, s.entity_type, s.slug, s.title,
           coalesce(search_snippet(s.excerpt_words, s.excerpt_keys, s.terms), '') AS snippet,
           s.score,
           count(*) OVER () AS total
      FROM scored s
     ORDER BY s.score DESC, s.indexed_at DESC, s.translation_id
     LIMIT least(greatest(coalesce(p_limit, 20), 1), 100)
    OFFSET greatest(coalesce(p_offset, 0), 0)
$$;
