-- Every imported draft records the published revision it was edited from.
-- Import, publish and rollback compare that baseline with the current
-- published pointer inside the festival row lock, so a revision that was
-- prepared before someone else published is refused instead of silently
-- discarding that publication.
--
-- Existing rows keep NULL. NULL means "prepared with no published revision",
-- which is only valid for the first catalog of a festival.
ALTER TABLE festival_revisions
    ADD COLUMN base_revision_id UUID NULL;

ALTER TABLE festival_revisions
    ADD CONSTRAINT fk_festival_revisions_base_revision
        FOREIGN KEY (base_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_festival_revisions_base_revision_not_self
        CHECK (base_revision_id IS NULL OR base_revision_id <> id);

CREATE INDEX ix_festival_revisions_base_revision
    ON festival_revisions (base_revision_id);
