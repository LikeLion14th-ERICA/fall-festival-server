-- Map filters follow the approved design: restrooms, photo booths, smoking
-- areas and trash bins. A PLACE pin outside these four has no filter group and
-- is shown only under the client's "all" filter.
--
-- The earlier broad groups (STUDENT_COUNCIL, EXPERIENCE, CONVENIENCE,
-- FOOD_AND_BEVERAGE, PERFORMANCE) have no one-to-one mapping to the new ones.
-- Existing pins therefore lose their group instead of being guessed into a new
-- one, and the old labels are removed. A revision that needs the new filters
-- supplies them through an approved catalog import.

ALTER TABLE map_pins
    DROP CONSTRAINT ck_map_pins_filter_group;

ALTER TABLE map_pin_filter_group_translations
    DROP CONSTRAINT ck_map_pin_filter_group_translations_group;

UPDATE map_pins
SET filter_group = NULL
WHERE filter_group IS NOT NULL;

DELETE FROM map_pin_filter_group_translations;

ALTER TABLE map_pins
    ADD CONSTRAINT ck_map_pins_filter_group
    CHECK (
        filter_group IS NULL
        OR filter_group IN ('RESTROOM', 'PHOTO_BOOTH', 'SMOKING_AREA', 'TRASH_BIN')
    );

ALTER TABLE map_pin_filter_group_translations
    ADD CONSTRAINT ck_map_pin_filter_group_translations_group
    CHECK (filter_group IN ('RESTROOM', 'PHOTO_BOOTH', 'SMOKING_AREA', 'TRASH_BIN'));
