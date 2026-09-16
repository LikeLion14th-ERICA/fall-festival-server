CREATE TABLE admin_audit_events (
    id UUID NOT NULL,
    admin_id UUID NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_admin_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_admin_audit_events_admin
        FOREIGN KEY (admin_id) REFERENCES admin_accounts(id) ON DELETE RESTRICT,
    CONSTRAINT ck_admin_audit_events_action
        CHECK (action ~ '^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$'),
    CONSTRAINT ck_admin_audit_events_resource_type
        CHECK (resource_type ~ '^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$'),
    CONSTRAINT ck_admin_audit_events_resource_id
        CHECK (resource_id IS NULL OR resource_id ~ '[^[:space:]]'),
    CONSTRAINT ck_admin_audit_events_request_id
        CHECK (request_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')
);

CREATE INDEX ix_admin_audit_events_admin_occurred_at
    ON admin_audit_events (admin_id, occurred_at DESC);

CREATE INDEX ix_admin_audit_events_request_id
    ON admin_audit_events (request_id);
