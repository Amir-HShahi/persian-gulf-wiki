-- Phase 2: the article core. An Article is the wiki entry itself -- the thing that carries
-- prose about a subject (or about nothing typed, for GENERIC). Its actual content lives one
-- level down, per language, in article_translations, and each translation's content history
-- lives another level down in article_revisions. Nothing here is publish-visible on its own:
-- publication/moderation status lives entirely on the revision, not the article or the
-- translation.

-- The wiki entry. subject_id is nullable -- a GENERIC article (e.g. a thematic essay with no
-- single typed real-world referent) has no subject to hang off. Deleting the user who
-- started an article does not delete the article: ON DELETE SET NULL, same reasoning as
-- sources.created_by_user_id in V15 -- prose outlives the account that began it.
CREATE TABLE articles (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id          UUID REFERENCES subjects (id),
    -- Derived, never client-chosen when subject_id is set: ArticleService reads it off
    -- subjects.kind by name. Only a subject-less (GENERIC) article gets it from the client
    -- directly. The CHECK list mirrors EntityType exactly, same maintenance contract as
    -- subjects.kind's CHECK in V15 -- a new value means a migration alongside the enum.
    entity_type         VARCHAR(20) NOT NULL,
    -- BCP-47 tag (e.g. "fa", "en", "ar") of the translation that is this article's source of
    -- truth for facts -- the one every other translation's OUTDATED/UP_TO_DATE state is
    -- judged against. Not a foreign key to article_translations.language because the
    -- canonical translation is created atomically with the article itself (see
    -- ArticleService.create); the language code is enough to find it via the
    -- (article_id, language) unique constraint below.
    canonical_language  VARCHAR(20) NOT NULL,
    created_by_user_id  UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as subjects.dev_marker / sources.dev_marker in V15: non-null only on rows
    -- minted by the dev-profile fixture endpoint, which is what makes handing an arbitrary
    -- id to the matching DELETE route safe.
    dev_marker          VARCHAR(40),
    CONSTRAINT ck_articles_entity_type
        CHECK (entity_type IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES', 'STRAIT', 'GENERIC'))
);

CREATE INDEX idx_articles_subject ON articles (subject_id);
CREATE INDEX idx_articles_entity_type ON articles (entity_type);
CREATE INDEX idx_articles_dev_marker ON articles (dev_marker) WHERE dev_marker IS NOT NULL;

-- One row per language an article has been written in. current_revision_id/source_revision_id
-- are added as separate ALTER TABLE statements below, once article_revisions exists -- the
-- two tables reference each other (a revision belongs to a translation; a translation points
-- at its current and source revision), so neither can carry a NOT NULL/inline FK to the other
-- at CREATE TABLE time without forward-declaring a table that doesn't exist yet.
CREATE TABLE article_translations (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    article_id           UUID NOT NULL REFERENCES articles (id) ON DELETE CASCADE,
    language             VARCHAR(20) NOT NULL,
    -- Globally unique, not scoped per language or per article -- a deliberate call: a slug
    -- resolves to exactly one translation without needing a language segment in the URL
    -- (e.g. /articles/hormuz-strait, not /articles/en/hormuz-strait). The tradeoff is that
    -- two translations of the same article in different languages cannot share a slug and
    -- must be told apart by spelling, which is accepted as the simpler URL shape wins here.
    slug                 VARCHAR(200) NOT NULL,
    current_revision_id  UUID,
    source_revision_id   UUID,
    -- UP_TO_DATE/OUTDATED/INDEPENDENT -- see ArticleTranslation.TranslationState for what
    -- each means; enforced here so a bad value can never reach the row regardless of write
    -- path.
    translation_state    VARCHAR(20) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_article_translations_article_language UNIQUE (article_id, language),
    CONSTRAINT uq_article_translations_slug UNIQUE (slug),
    CONSTRAINT ck_article_translations_state
        CHECK (translation_state IN ('UP_TO_DATE', 'OUTDATED', 'INDEPENDENT'))
);

CREATE INDEX idx_article_translations_article ON article_translations (article_id);

-- The append-only content history for one translation. A translation's visible content at
-- any moment is whichever revision its current_revision_id points at -- this table itself
-- has no notion of "the current one" beyond that pointer, so a rejected or superseded
-- revision is never deleted, only left unpointed-to.
CREATE TABLE article_revisions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    translation_id       UUID NOT NULL REFERENCES article_translations (id) ON DELETE CASCADE,
    -- 1-based, per translation (see uq_article_revisions_translation_number below). Lets a
    -- client display "revision 3 of 5" without counting rows, and gives parent_revision_id a
    -- cheap sanity check (a parent's number must be lower).
    revision_number      INT NOT NULL,
    parent_revision_id   UUID REFERENCES article_revisions (id),
    title                TEXT NOT NULL,
    -- Structured content, not flat markdown/HTML -- Phase 4 (renderer) reads this as a tree.
    -- jsonb rather than json: the column is read far more often than written, and jsonb's
    -- binary form skips reparsing on every read at the cost of a slightly slower insert.
    body                 JSONB NOT NULL,
    summary              TEXT,
    -- DRAFT/PENDING/CHANGES_REQUESTED/APPROVED/REJECTED. CHANGES_REQUESTED is not in the
    -- original ERD -- see ArticleRevision.RevisionStatus for why it was added: Phase 3's
    -- moderator REQUEST_CHANGES decision has no other status to land a revision in that is
    -- distinct from both DRAFT (never reviewed) and PENDING (awaiting review).
    status               VARCHAR(25) NOT NULL,
    -- Not ON DELETE SET NULL like articles.created_by_user_id/sources.created_by_user_id:
    -- those describe who *started* a thing that outlives them, but a revision's author_id is
    -- also RevisionNotEditableException/NotRevisionAuthorException's authorization key (only
    -- the author may edit their own draft) -- turning it NULL on account deletion would
    -- silently strip that check rather than fail loudly. Deleting a user account with
    -- authored revisions is out of scope for this phase.
    author_id            UUID NOT NULL REFERENCES users (id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_article_revisions_translation_number UNIQUE (translation_id, revision_number),
    CONSTRAINT ck_article_revisions_status
        CHECK (status IN ('DRAFT', 'PENDING', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_article_revisions_translation ON article_revisions (translation_id);
CREATE INDEX idx_article_revisions_status ON article_revisions (status);

-- Deferred FKs, added now that both tables exist. See the comment on article_translations
-- above for why these couldn't be declared inline.
ALTER TABLE article_translations
    ADD CONSTRAINT fk_article_translations_current_revision
        FOREIGN KEY (current_revision_id) REFERENCES article_revisions (id);

ALTER TABLE article_translations
    ADD CONSTRAINT fk_article_translations_source_revision
        FOREIGN KEY (source_revision_id) REFERENCES article_revisions (id);
