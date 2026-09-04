CREATE TABLE participants (
    id UUID PRIMARY KEY,
    owner_key VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE participant_sessions (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    csrf_token VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_participant_sessions_participant ON participant_sessions(participant_id);
CREATE INDEX idx_participant_sessions_expiry ON participant_sessions(expires_at);

CREATE TABLE counters (
    participant_id UUID PRIMARY KEY REFERENCES participants(id) ON DELETE CASCADE,
    value SMALLINT NOT NULL DEFAULT 0 CHECK (value >= 0 AND value <= 10),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE counter_operations (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    delta SMALLINT NOT NULL CHECK (delta IN (-1, 1)),
    result_value SMALLINT NOT NULL CHECK (result_value >= 0 AND result_value <= 10),
    result_version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_counter_operation UNIQUE (participant_id, operation_id)
);

CREATE INDEX idx_counter_operations_created ON counter_operations(created_at);

CREATE TABLE push_subscriptions (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL,
    endpoint_hash CHAR(64) NOT NULL UNIQUE,
    p256dh TEXT NOT NULL,
    auth TEXT NOT NULL,
    expiration_time TIMESTAMP WITH TIME ZONE NULL,
    locale VARCHAR(16) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_accepted_at TIMESTAMP WITH TIME ZONE NULL,
    last_failure_at TIMESTAMP WITH TIME ZONE NULL,
    deactivated_at TIMESTAMP WITH TIME ZONE NULL,
    deactivation_reason VARCHAR(64) NULL
);

CREATE INDEX idx_push_subscriptions_participant ON push_subscriptions(participant_id, status);

CREATE TABLE clock_test_runs (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'STOPPED')),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_scheduled_at TIMESTAMP WITH TIME ZONE NULL,
    next_sequence SMALLINT NOT NULL,
    sent_count SMALLINT NOT NULL DEFAULT 0,
    failed_count SMALLINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_clock_test_runs_due ON clock_test_runs(status, next_scheduled_at);
CREATE INDEX idx_clock_test_runs_participant ON clock_test_runs(participant_id, started_at);

CREATE TABLE notification_events (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    run_id UUID NULL REFERENCES clock_test_runs(id) ON DELETE SET NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('TEST_NOW', 'CLOCK')),
    sequence SMALLINT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('SCHEDULED', 'SENDING', 'ACCEPTED', 'FAILED', 'ACKNOWLEDGED')),
    message_id VARCHAR(64) NOT NULL UNIQUE,
    notification_tag VARCHAR(128) NOT NULL UNIQUE,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NULL,
    acknowledged_at TIMESTAMP WITH TIME ZONE NULL,
    client_received_at TIMESTAMP WITH TIME ZONE NULL,
    error_code VARCHAR(64) NULL,
    terminal_counted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_clock_sequence UNIQUE (run_id, sequence)
);

CREATE INDEX idx_notification_events_history ON notification_events(participant_id, created_at);

CREATE TABLE push_deliveries (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES notification_events(id) ON DELETE CASCADE,
    subscription_id UUID NOT NULL REFERENCES push_subscriptions(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RETRY', 'ACCEPTED', 'FAILED')),
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    response_code INTEGER NULL,
    sent_at TIMESTAMP WITH TIME ZONE NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NULL,
    error_code VARCHAR(128) NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_event_subscription UNIQUE (event_id, subscription_id)
);

CREATE INDEX idx_push_deliveries_due ON push_deliveries(status, next_attempt_at);
