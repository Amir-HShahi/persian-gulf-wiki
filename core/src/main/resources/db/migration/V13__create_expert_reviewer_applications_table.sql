CREATE TABLE expert_reviewer_applications (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    applicant_user_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    entity_type         VARCHAR(50) NOT NULL,
    justification       TEXT NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewed_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_expert_reviewer_applications_applicant ON expert_reviewer_applications (applicant_user_id);

-- Only one PENDING application per (applicant, entity_type) at a time:
CREATE UNIQUE INDEX uq_expert_reviewer_applications_pending
    ON expert_reviewer_applications (applicant_user_id, entity_type)
    WHERE status = 'PENDING';
