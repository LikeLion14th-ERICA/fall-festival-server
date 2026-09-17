CREATE TABLE admin_idempotency_records (
    id UUID NOT NULL,
    scope_hash CHAR(64) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    lease_token UUID NULL,
    lease_expires_at TIMESTAMP WITH TIME ZONE NULL,
    response_status SMALLINT NULL,
    response_content_type VARCHAR(128) NULL,
    response_body TEXT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT pk_admin_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uq_admin_idempotency_scope_key UNIQUE (scope_hash, key_hash),
    CONSTRAINT ck_admin_idempotency_scope_hash
        CHECK (scope_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_admin_idempotency_key_hash
        CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_admin_idempotency_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_admin_idempotency_state
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_admin_idempotency_in_progress
        CHECK (
            (state = 'IN_PROGRESS'
                AND lease_token IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND response_status IS NULL
                AND response_content_type IS NULL
                AND response_body IS NULL
                AND completed_at IS NULL)
            OR
            (state = 'COMPLETED'
                AND lease_token IS NULL
                AND lease_expires_at IS NULL
                AND response_status BETWEEN 200 AND 299
                AND completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_admin_idempotency_response_content
        CHECK (
            (response_body IS NULL AND response_content_type IS NULL)
            OR
            (response_body IS NOT NULL AND response_content_type IS NOT NULL)
        )
);

CREATE INDEX ix_admin_idempotency_completed_at
    ON admin_idempotency_records (completed_at)
    WHERE state = 'COMPLETED';
