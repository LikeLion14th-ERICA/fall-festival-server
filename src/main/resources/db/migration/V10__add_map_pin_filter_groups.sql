-- Parent filter groups for map pins. The existing category column remains the
-- free-form, fine-grained pin type used by the detail popup.
--
-- This migration is intentionally additive. Existing V8 rows are not guessed
-- from category and therefore remain NULL until an approved revision import
-- supplies the group and its locale label.

ALTER TABLE map_pins
    ADD COLUMN filter_group TEXT NULL;

ALTER TABLE map_pins
    ADD CONSTRAINT ck_map_pins_filter_group
    CHECK (
        filter_group IS NULL
        OR filter_group IN (
            'STUDENT_COUNCIL',
            'EXPERIENCE',
            'CONVENIENCE',
            'FOOD_AND_BEVERAGE',
            'PERFORMANCE'
        )
    );

CREATE TABLE map_pin_filter_group_translations (
    festival_revision_id UUID NOT NULL,
    filter_group TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    label TEXT NOT NULL,
    CONSTRAINT pk_map_pin_filter_group_translations
        PRIMARY KEY (festival_revision_id, filter_group, locale),
    CONSTRAINT fk_map_pin_filter_group_translations_revision
        FOREIGN KEY (festival_revision_id)
        REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_map_pin_filter_group_translations_group
        CHECK (filter_group IN (
            'STUDENT_COUNCIL',
            'EXPERIENCE',
            'CONVENIENCE',
            'FOOD_AND_BEVERAGE',
            'PERFORMANCE'
        )),
    CONSTRAINT ck_map_pin_filter_group_translations_locale_not_blank
        CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_map_pin_filter_group_translations_label_not_blank
        CHECK (btrim(label) <> '')
);
