-- V10 is intentionally additive for the live catalog. The V1/V3 singleton
-- tables remain intact as a compatibility mirror, while these tables retain
-- one immutable guide row for every revision. Existing rows are copied; no
-- existing content is deleted or rewritten. A legacy partial ticket schedule
-- is normalized only in the new revision copy to the explicit unconfigured
-- state because dates and all daily times must be present together.

CREATE TABLE ticket_guide_revisions (
    festival_revision_id UUID NOT NULL,
    id SMALLINT NOT NULL,
    unit_price_amount INTEGER NULL,
    account_bank_name TEXT NULL,
    account_number TEXT NULL,
    account_holder TEXT NULL,
    transfer_link_label TEXT NULL,
    transfer_link_url TEXT NULL,
    map_id TEXT NULL,
    place_id TEXT NULL,
    pin_id TEXT NULL,
    map_version TEXT NULL,
    instructions TEXT[] NOT NULL DEFAULT '{}',
    festival_start_date DATE NULL,
    festival_end_date DATE NULL,
    daily_transfer_open_time TIME NULL,
    daily_transfer_close_time TIME NULL,
    daily_pickup_open_time TIME NULL,
    daily_pickup_close_time TIME NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_ticket_guide_revisions PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT fk_ticket_guide_revisions_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ticket_guide_revisions_singleton CHECK (id = 1),
    CONSTRAINT ck_ticket_guide_revisions_map_target_together CHECK (
        (map_id IS NULL AND place_id IS NULL AND pin_id IS NULL AND map_version IS NULL)
        OR (
            map_id IS NOT NULL AND place_id IS NOT NULL AND pin_id IS NOT NULL
            AND map_version IS NOT NULL
        )
    ),
    CONSTRAINT fk_ticket_guide_revisions_current_map
        FOREIGN KEY (festival_revision_id, map_id, map_version)
        REFERENCES maps(festival_revision_id, id, current_version) ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_guide_revisions_map_pin
        FOREIGN KEY (festival_revision_id, map_id, map_version, pin_id, place_id)
        REFERENCES map_pins(festival_revision_id, map_id, map_version, id, place_id) ON DELETE RESTRICT
);

CREATE TABLE stamp_guide_revisions (
    festival_revision_id UUID NOT NULL,
    id SMALLINT NOT NULL,
    title TEXT NOT NULL,
    dates DATE[] NOT NULL DEFAULT '{}',
    instructions TEXT[] NOT NULL DEFAULT '{}',
    reward_name TEXT NOT NULL,
    reward_location_text TEXT NULL,
    reward_hours_text TEXT NULL,
    reward_notice TEXT NOT NULL,
    qr_value TEXT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stamp_guide_revisions PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT fk_stamp_guide_revisions_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_stamp_guide_revisions_singleton CHECK (id = 1)
);

-- V8 guarantees one published revision before the catalog tables are usable.
-- A legacy stamp row may still have a NULL revision from V4; attach its copy
-- to that published revision without changing the legacy source row.
DO $$
DECLARE
    published_revision UUID;
BEGIN
    SELECT id INTO published_revision
    FROM festival_revisions
    WHERE state = 'published';

    IF published_revision IS NULL THEN
        RAISE EXCEPTION 'V10 requires a published festival revision';
    END IF;

    INSERT INTO ticket_guide_revisions (
        festival_revision_id, id, unit_price_amount, account_bank_name,
        account_number, account_holder, transfer_link_label, transfer_link_url,
        map_id, place_id, pin_id, map_version, instructions,
        festival_start_date, festival_end_date, daily_transfer_open_time,
        daily_transfer_close_time, daily_pickup_open_time,
        daily_pickup_close_time, updated_at
    )
    SELECT festival_revision_id, id, unit_price_amount, account_bank_name,
           account_number, account_holder, transfer_link_label, transfer_link_url,
           map_id, place_id, pin_id, map_version, instructions,
           CASE WHEN festival_start_date IS NOT NULL AND festival_end_date IS NOT NULL
                  AND daily_transfer_open_time IS NOT NULL AND daily_transfer_close_time IS NOT NULL
                  AND daily_pickup_open_time IS NOT NULL AND daily_pickup_close_time IS NOT NULL
                THEN festival_start_date END,
           CASE WHEN festival_start_date IS NOT NULL AND festival_end_date IS NOT NULL
                  AND daily_transfer_open_time IS NOT NULL AND daily_transfer_close_time IS NOT NULL
                  AND daily_pickup_open_time IS NOT NULL AND daily_pickup_close_time IS NOT NULL
                THEN festival_end_date END,
           CASE WHEN festival_start_date IS NOT NULL AND festival_end_date IS NOT NULL
                  AND daily_transfer_open_time IS NOT NULL AND daily_transfer_close_time IS NOT NULL
                  AND daily_pickup_open_time IS NOT NULL AND daily_pickup_close_time IS NOT NULL
                THEN daily_transfer_open_time END,
           CASE WHEN festival_start_date IS NOT NULL AND festival_end_date IS NOT NULL
                  AND daily_transfer_open_time IS NOT NULL AND daily_transfer_close_time IS NOT NULL
                  AND daily_pickup_open_time IS NOT NULL AND daily_pickup_close_time IS NOT NULL
                THEN daily_transfer_close_time END,
           CASE WHEN festival_start_date IS NOT NULL AND festival_end_date IS NOT NULL
                  AND daily_transfer_open_time IS NOT NULL AND daily_transfer_close_time IS NOT NULL
                  AND daily_pickup_open_time IS NOT NULL AND daily_pickup_close_time IS NOT NULL
                THEN daily_pickup_open_time END,
           CASE WHEN festival_start_date IS NOT NULL AND festival_end_date IS NOT NULL
                  AND daily_transfer_open_time IS NOT NULL AND daily_transfer_close_time IS NOT NULL
                  AND daily_pickup_open_time IS NOT NULL AND daily_pickup_close_time IS NOT NULL
                THEN daily_pickup_close_time END,
           updated_at
    FROM ticket_guide;

    INSERT INTO stamp_guide_revisions (
        festival_revision_id, id, title, dates, instructions, reward_name,
        reward_location_text, reward_hours_text, reward_notice, qr_value, updated_at
    )
    SELECT COALESCE(festival_revision_id, published_revision), id, title, dates,
           instructions, reward_name, reward_location_text, reward_hours_text,
           reward_notice, qr_value, updated_at
    FROM stamp_guide;
END $$;

CREATE INDEX ix_ticket_guide_revisions_revision
    ON ticket_guide_revisions (festival_revision_id);

CREATE INDEX ix_stamp_guide_revisions_revision
    ON stamp_guide_revisions (festival_revision_id);

CREATE TABLE catalog_revision_audit (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
    festival_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    source_revision_id UUID NULL,
    actor VARCHAR(100) NOT NULL,
    manifest_sha256 CHAR(64) NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_catalog_revision_audit PRIMARY KEY (id),
    CONSTRAINT fk_catalog_revision_audit_festival
        FOREIGN KEY (festival_id) REFERENCES festivals(id) ON DELETE RESTRICT,
    CONSTRAINT fk_catalog_revision_audit_revision
        FOREIGN KEY (revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_catalog_revision_audit_source_revision
        FOREIGN KEY (source_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_catalog_revision_audit_action
        CHECK (action IN ('IMPORT', 'VALIDATE', 'PUBLISH', 'ROLLBACK')),
    CONSTRAINT ck_catalog_revision_audit_actor_not_blank
        CHECK (btrim(actor) <> ''),
    CONSTRAINT ck_catalog_revision_audit_hash
        CHECK (manifest_sha256 IS NULL OR manifest_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_catalog_revision_audit_revision_created
    ON catalog_revision_audit (revision_id, created_at DESC);

CREATE INDEX ix_catalog_revision_audit_festival_created
    ON catalog_revision_audit (festival_id, created_at DESC);
