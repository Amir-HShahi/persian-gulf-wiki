CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id   UUID REFERENCES users (id) ON DELETE SET NULL,
    action          VARCHAR(100) NOT NULL,
    target_user_id  UUID REFERENCES users (id) ON DELETE SET NULL,
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_actor_user_id ON audit_log (actor_user_id);
CREATE INDEX idx_audit_log_target_user_id ON audit_log (target_user_id);
CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);
