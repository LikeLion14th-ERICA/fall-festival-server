-- LOVE-001 is operational personal data, never part of a catalog revision.
CREATE TABLE love_letter_settings (
    festival_id UUID PRIMARY KEY REFERENCES festivals(id) ON DELETE RESTRICT,
    opens_at TIMESTAMPTZ NOT NULL,
    closes_at TIMESTAMPTZ NOT NULL,
    consent_version VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_love_letter_period CHECK (opens_at < closes_at)
);

CREATE TABLE love_letter_participants (
    id UUID PRIMARY KEY,
    festival_id UUID NOT NULL REFERENCES festivals(id) ON DELETE RESTRICT,
    token_sha256 CHAR(64) UNIQUE,
    restricted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_love_letter_token CHECK (token_sha256 IS NULL OR token_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_love_letter_participant_festival UNIQUE (id, festival_id)
);

CREATE TABLE love_letters (
    id UUID PRIMARY KEY,
    festival_id UUID NOT NULL,
    author_id UUID NOT NULL,
    created_date DATE NOT NULL,
    gender VARCHAR(6) NOT NULL CHECK (gender IN ('MALE', 'FEMALE')),
    name_cipher TEXT NOT NULL,
    body_cipher TEXT NOT NULL,
    contact_cipher TEXT NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    consent_version VARCHAR(64) NOT NULL,
    consent_at TIMESTAMPTZ NOT NULL,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_love_letter_author FOREIGN KEY (author_id, festival_id)
      REFERENCES love_letter_participants(id, festival_id) ON DELETE CASCADE,
    CONSTRAINT uq_love_letter_author_date UNIQUE (author_id, created_date),
    CONSTRAINT uq_love_letter_id_festival UNIQUE (id, festival_id)
);
CREATE INDEX ix_love_letter_pool ON love_letters (festival_id, gender, blocked) WHERE blocked = FALSE;

CREATE TABLE love_letter_participation_days (
    participant_id UUID NOT NULL REFERENCES love_letter_participants(id) ON DELETE CASCADE,
    operating_date DATE NOT NULL,
    letter_id UUID NOT NULL UNIQUE REFERENCES love_letters(id) ON DELETE CASCADE,
    state VARCHAR(12) NOT NULL CHECK (state IN ('SEEDED', 'PENDING', 'COMPLETED')),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (participant_id, operating_date),
    CONSTRAINT ck_love_letter_day_state CHECK
      ((state IN ('SEEDED', 'PENDING') AND completed_at IS NULL) OR (state = 'COMPLETED' AND completed_at IS NOT NULL))
);

CREATE TABLE love_letter_exchanges (
    id UUID PRIMARY KEY,
    festival_id UUID NOT NULL REFERENCES festivals(id) ON DELETE CASCADE,
    receiver_id UUID NOT NULL REFERENCES love_letter_participants(id) ON DELETE CASCADE,
    letter_id UUID NOT NULL UNIQUE REFERENCES love_letters(id) ON DELETE CASCADE,
    operating_date DATE NOT NULL,
    opened_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_love_letter_receiver_day UNIQUE (receiver_id, operating_date)
);

CREATE TABLE love_letter_requests (
    participant_id UUID NOT NULL REFERENCES love_letter_participants(id) ON DELETE CASCADE,
    operating_date DATE NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hmac CHAR(64) NOT NULL,
    letter_id UUID REFERENCES love_letters(id) ON DELETE CASCADE,
    exchange_id UUID REFERENCES love_letter_exchanges(id) ON DELETE CASCADE,
    PRIMARY KEY (participant_id, idempotency_key),
    CONSTRAINT ck_love_letter_request_result CHECK ((letter_id IS NULL) <> (exchange_id IS NULL))
);

CREATE TABLE love_letter_invitations (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL REFERENCES love_letter_participants(id) ON DELETE CASCADE,
    token_sha256 CHAR(64) NOT NULL UNIQUE CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    operating_date DATE NOT NULL,
    consumed_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE love_letter_reports (
    id UUID PRIMARY KEY,
    exchange_id UUID NOT NULL REFERENCES love_letter_exchanges(id) ON DELETE CASCADE,
    reporter_id UUID NOT NULL REFERENCES love_letter_participants(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_love_letter_report_once UNIQUE (exchange_id, reporter_id)
);
