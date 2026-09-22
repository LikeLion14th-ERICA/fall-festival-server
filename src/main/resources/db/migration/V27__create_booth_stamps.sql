-- Booth stamps (STAMP-001, 2026-09-22 revision). Every participating booth
-- shows its own QR whose link carries a random token; a participant collects
-- at most one stamp per booth per festival day and four per day.
--
-- Booths and their token hashes are catalog content: they belong to a festival
-- revision and change only through catalog import and publish. The raw token
-- lives only in the QR link; the catalog keeps its SHA-256 so a published
-- manifest can sit in a public repository.
--
-- Participants, collected stamps and claimed rewards are operational data
-- scoped to the festival, outside the catalog, like crowding and notices. A
-- participant is an anonymous browser: the server hands out a random cookie
-- value and stores only its SHA-256. There is no account, name or recovery.

CREATE TABLE stamp_booths (
    festival_revision_id UUID NOT NULL,
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    sort_order INT NOT NULL,
    CONSTRAINT pk_stamp_booths PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT fk_stamp_booths_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT uq_stamp_booths_sort_order UNIQUE (festival_revision_id, sort_order),
    CONSTRAINT ck_stamp_booths_id CHECK (id ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    CONSTRAINT ck_stamp_booths_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_stamp_booths_sort_order_positive CHECK (sort_order > 0)
);

-- valid_date NULL means the token works on every festival day; a dated token
-- works only on that day, so a photographed QR expires at midnight.
CREATE TABLE stamp_booth_tokens (
    festival_revision_id UUID NOT NULL,
    booth_id TEXT NOT NULL,
    valid_date DATE NULL,
    token_sha256 CHAR(64) NOT NULL,
    CONSTRAINT pk_stamp_booth_tokens PRIMARY KEY (festival_revision_id, token_sha256),
    CONSTRAINT fk_stamp_booth_tokens_booth
        FOREIGN KEY (festival_revision_id, booth_id)
        REFERENCES stamp_booths(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_stamp_booth_tokens_sha256 CHECK (token_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_stamp_booth_tokens_booth_day
    ON stamp_booth_tokens (festival_revision_id, booth_id, COALESCE(valid_date, DATE '0001-01-01'));

CREATE TABLE stamp_participants (
    id UUID NOT NULL,
    festival_id UUID NOT NULL REFERENCES festivals(id) ON DELETE RESTRICT,
    token_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stamp_participants PRIMARY KEY (id),
    CONSTRAINT uq_stamp_participants_token UNIQUE (token_sha256),
    CONSTRAINT ck_stamp_participants_sha256 CHECK (token_sha256 ~ '^[0-9a-f]{64}$')
);

-- One row per participant, festival day and booth: the primary key is the
-- "one stamp per booth per day" rule.
CREATE TABLE stamp_collections (
    participant_id UUID NOT NULL REFERENCES stamp_participants(id) ON DELETE CASCADE,
    operating_date DATE NOT NULL,
    booth_id TEXT NOT NULL,
    collected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stamp_collections PRIMARY KEY (participant_id, operating_date, booth_id)
);

CREATE TABLE stamp_rewards (
    participant_id UUID NOT NULL REFERENCES stamp_participants(id) ON DELETE CASCADE,
    operating_date DATE NOT NULL,
    claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stamp_rewards PRIMARY KEY (participant_id, operating_date)
);
