-- Daily START for booth stamps (STAMP-001). A participant presses START once
-- per festival day: the start screen is hidden from that moment until the
-- next day, and a booth QR opened before today's START collects nothing. The
-- anonymous participant (V27) stays the same across days; this table records
-- the days it started.

CREATE TABLE stamp_participant_days (
    participant_id UUID NOT NULL REFERENCES stamp_participants(id) ON DELETE CASCADE,
    operating_date DATE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stamp_participant_days PRIMARY KEY (participant_id, operating_date)
);

-- Rows written under V27 had no daily START: a day with a collected stamp, or
-- the day the participant was created (festival time zone Asia/Seoul), counts
-- as started so an open card is not sent back to the start screen.
INSERT INTO stamp_participant_days (participant_id, operating_date, started_at)
SELECT participant_id, operating_date, min(collected_at)
FROM stamp_collections
GROUP BY participant_id, operating_date
ON CONFLICT DO NOTHING;

INSERT INTO stamp_participant_days (participant_id, operating_date, started_at)
SELECT id, (created_at AT TIME ZONE 'Asia/Seoul')::date, created_at
FROM stamp_participants
ON CONFLICT DO NOTHING;
