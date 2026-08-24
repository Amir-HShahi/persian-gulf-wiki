-- Expert Reviewer roles are scoped per entity type (Species, Port, Island, OilField, ...),
-- while Moderator/Admin remain global (entity_type NULL). No check constraint on the
-- entity_type values yet -- there's no fixed source-of-truth enum for domain entity types
-- in the codebase yet, so this stays a free-form nullable VARCHAR for now.
ALTER TABLE user_roles
    ADD COLUMN entity_type VARCHAR(50);

ALTER TABLE user_roles
    DROP CONSTRAINT uq_user_roles_user_id_role;

ALTER TABLE user_roles
    ADD CONSTRAINT uq_user_roles_user_id_role_entity_type UNIQUE (user_id, role, entity_type);

-- Postgres UNIQUE constraints do not treat NULL as equal to NULL, so the constraint above
-- would not stop a user from being granted the same global role (entity_type NULL) twice.
-- A partial unique index closes that gap for the global-role case specifically.
CREATE UNIQUE INDEX uq_user_roles_global ON user_roles (user_id, role) WHERE entity_type IS NULL;
