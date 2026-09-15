-- Backs GET /api/v2/crowding (HOME-001, docs/wiki/product/home.md). One row
-- per operating day holding the operator-chosen level; KST 00:00 reset means
-- "no row for today" rather than carrying a value over, so an empty table is
-- the correct starting state (defaults to RELAXED during operating hours).
--
-- No admin write endpoint exists yet: PUT /admin/crowding needs A's
-- authentication/authorization foundation first (docs/wiki/engineering/security.md),
-- so rows here are only ever inserted manually until that lands.
--
-- festival_day_id is nullable for the same reason as stamp_guide/ticket_guide's
-- festival_revision_id: festival_days has no seeded rows yet.
CREATE TABLE crowding_state (
    operating_day DATE PRIMARY KEY,
    festival_day_id UUID NULL REFERENCES festival_days(id),
    level TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT crowding_state_level_values CHECK (level IN ('RELAXED', 'MODERATE', 'CROWDED', 'FULL'))
);
