-- Preserve V31 checksums and all existing settings while extending the window.
ALTER TABLE crowding_operating_hours
    DROP CONSTRAINT ck_crowding_operating_hours_window;

ALTER TABLE crowding_operating_hours
    ADD CONSTRAINT ck_crowding_operating_hours_window CHECK (
        opens_at < closes_at
        AND (opens_at AT TIME ZONE 'Asia/Seoul')::date = operating_date
        AND closes_at AT TIME ZONE 'Asia/Seoul'
            <= (operating_date + 1)::timestamp + INTERVAL '1 hour'
        AND EXTRACT(SECOND FROM opens_at) = 0
        AND EXTRACT(SECOND FROM closes_at) = 0
    );
