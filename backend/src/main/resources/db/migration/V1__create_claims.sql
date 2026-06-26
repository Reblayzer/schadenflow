CREATE TABLE claims (
    id           UUID PRIMARY KEY,
    claimant_id  UUID NOT NULL,
    title        VARCHAR(200) NOT NULL,
    description  TEXT NOT NULL,
    category     VARCHAR(100),
    amount       NUMERIC(12, 2) NOT NULL,
    state        VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_claims_state ON claims (state);
CREATE INDEX idx_claims_claimant_id ON claims (claimant_id);
