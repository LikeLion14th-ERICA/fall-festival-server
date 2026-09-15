-- Adds the festival_revision reference that docs/wiki/engineering/data-model.md
-- requires for all operational content, now that V2__create_festival_core.sql
-- exists. Nullable until a real festival_revisions row is seeded (A hasn't
-- decided/confirmed timing yet) — wire up the value and drop NULL once one
-- exists. See ticket_guide's identical column in V3 for the same pattern.
ALTER TABLE stamp_guide
    ADD COLUMN festival_revision_id UUID NULL REFERENCES festival_revisions(id);
