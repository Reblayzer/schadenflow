CREATE TABLE audit_entries (
    id          UUID PRIMARY KEY,
    claim_id    UUID NOT NULL REFERENCES claims (id),
    from_state  VARCHAR(20),
    to_state    VARCHAR(20) NOT NULL,
    actor_id    UUID NOT NULL,
    actor_role  VARCHAR(20) NOT NULL,
    reason      TEXT,
    timestamp   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_entries_claim_id ON audit_entries (claim_id);
