-- Provisional PR3 migration. V5 crowding_state remains intact as a legacy
-- compatibility table; this table is the revision-independent source of truth.
CREATE TABLE crowding_state_dynamic (
    festival_id UUID NOT NULL,
    operating_date DATE NOT NULL,
    level TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_crowding_state_dynamic PRIMARY KEY (festival_id, operating_date),
    CONSTRAINT fk_crowding_state_dynamic_festival
        FOREIGN KEY (festival_id) REFERENCES festivals(id) ON DELETE RESTRICT,
    CONSTRAINT ck_crowding_state_dynamic_level
        CHECK (level IN ('RELAXED', 'MODERATE', 'CROWDED', 'FULL'))
);

-- A blank FESTIVAL_ID is valid when the legacy table is empty. Once legacy
-- rows exist, an explicit festival id is mandatory so a row cannot silently
-- move to whichever festival happens to be configured first.
DO $$
DECLARE
    configured_festival_id UUID;
    legacy_row_count BIGINT;
BEGIN
    SELECT count(*) INTO legacy_row_count FROM crowding_state;
    IF legacy_row_count = 0 THEN
        RETURN;
    END IF;

    configured_festival_id := NULLIF('${festivalId}', '')::UUID;
    IF configured_festival_id IS NULL THEN
        RAISE EXCEPTION 'FESTIVAL_ID is required to migrate non-empty crowding_state';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM crowding_state legacy
        LEFT JOIN festival_days day ON day.id = legacy.festival_day_id
        LEFT JOIN festival_revisions revision ON revision.id = day.festival_revision_id
        WHERE day.id IS NULL
           OR revision.festival_id IS DISTINCT FROM configured_festival_id
           OR day.festival_date IS DISTINCT FROM legacy.operating_day
    ) THEN
        RAISE EXCEPTION 'crowding_state contains a festival/day/date mismatch';
    END IF;

    INSERT INTO crowding_state_dynamic (festival_id, operating_date, level, updated_at)
    SELECT configured_festival_id, operating_day, level, updated_at
    FROM crowding_state;
END
$$;
