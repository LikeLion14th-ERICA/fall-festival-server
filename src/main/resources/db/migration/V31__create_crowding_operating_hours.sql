-- Administrator hours are independent of catalog revisions. No backfill:
-- absent rows continue to use the currently published FestivalDay times.
CREATE TABLE crowding_operating_hours (
    festival_id UUID NOT NULL,
    operating_date DATE NOT NULL,
    opens_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closes_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_crowding_operating_hours PRIMARY KEY (festival_id, operating_date),
    CONSTRAINT fk_crowding_operating_hours_festival
        FOREIGN KEY (festival_id) REFERENCES festivals(id) ON DELETE RESTRICT,
    CONSTRAINT ck_crowding_operating_hours_window CHECK (
        opens_at < closes_at
        AND (opens_at AT TIME ZONE 'Asia/Seoul')::date = operating_date
        AND closes_at AT TIME ZONE 'Asia/Seoul' <= (operating_date + 1)::timestamp
        AND EXTRACT(SECOND FROM opens_at) = 0
        AND EXTRACT(SECOND FROM closes_at) = 0
    )
);
