-- The first slice of machine translation: where an article's translations were translated from,
-- and which revisions a model wrote rather than a person. Nothing in core writes these yet except
-- the dev profile's demo seeder and fixtures; the election and the worker that will fill them for
-- real come later and keep these columns as they are.

-- The translation the others are translated from. Not the same as canonical_language, which stays
-- the language the article was created in. SET NULL: deleting that translation leaves the article
-- with no source until one is chosen again.
ALTER TABLE articles
    ADD COLUMN source_translation_id UUID REFERENCES article_translations (id) ON DELETE SET NULL;

-- A machine translation is a translation whose current revision has is_machine_generated set; a
-- person's revision approved on top of it takes it over.
ALTER TABLE article_revisions
    ADD COLUMN is_machine_generated BOOLEAN NOT NULL DEFAULT false,
    -- The model that wrote the text, e.g. "qwen3.5:4b". Null on every revision a person wrote.
    ADD COLUMN machine_model VARCHAR(100),
    ADD CONSTRAINT ck_article_revisions_machine_model
        CHECK (is_machine_generated OR machine_model IS NULL);
