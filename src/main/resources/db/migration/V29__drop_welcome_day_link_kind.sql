-- WELCOME-001 (에리카 웰컴 데이) was removed as a product decision on 2026-09-22:
-- visiting-high-schooler turnout was too low, so the student council dropped
-- the feature. No manifest ever actually published a WELCOME_DAY link, but
-- this migration clears any existing rows defensively before narrowing the
-- allowed kind so a stray row cannot block the CHECK constraint change.

DELETE FROM festival_link_translations
WHERE (festival_revision_id, link_id) IN (
    SELECT festival_revision_id, id FROM festival_links WHERE kind = 'WELCOME_DAY'
);

DELETE FROM festival_links WHERE kind = 'WELCOME_DAY';

ALTER TABLE festival_links DROP CONSTRAINT ck_festival_links_kind;
ALTER TABLE festival_links ADD CONSTRAINT ck_festival_links_kind
    CHECK (kind IN ('UNIVERSITY_NOTICES', 'FAQ', 'OFFICIAL_CHANNEL'));
