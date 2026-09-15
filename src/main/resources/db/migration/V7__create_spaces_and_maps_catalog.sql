-- Read-only public catalog backing the API v2 spaces, maps, pins and places
-- endpoints. Operational content is deliberately not seeded here: approved
-- names, translations, images, coordinates and ticket-zone locations are
-- still pending (docs/wiki/product/decisions.md).

CREATE TABLE spaces (
    festival_revision_id UUID NOT NULL,
    id TEXT NOT NULL,
    category TEXT NOT NULL,
    image_url TEXT NOT NULL,
    image_width INTEGER NOT NULL,
    image_height INTEGER NOT NULL,
    CONSTRAINT pk_spaces PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT fk_spaces_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_spaces_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_spaces_category CHECK (category IN ('BOOTH', 'PUB', 'FLEA_MARKET')),
    CONSTRAINT ck_spaces_image_url_not_blank CHECK (btrim(image_url) <> ''),
    CONSTRAINT ck_spaces_image_dimensions CHECK (image_width > 0 AND image_height > 0)
);

CREATE TABLE space_translations (
    festival_revision_id UUID NOT NULL,
    space_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    name TEXT NOT NULL,
    image_alt TEXT NOT NULL,
    location_text TEXT NOT NULL,
    operator_text TEXT NULL,
    hours_text TEXT NULL,
    description_text TEXT NULL,
    experience_text TEXT NULL,
    contact_label TEXT NULL,
    contact_url TEXT NULL,
    CONSTRAINT pk_space_translations PRIMARY KEY (festival_revision_id, space_id, locale),
    CONSTRAINT fk_space_translations_space
        FOREIGN KEY (festival_revision_id, space_id)
        REFERENCES spaces(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_space_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_space_translations_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_space_translations_image_alt_not_blank CHECK (btrim(image_alt) <> ''),
    CONSTRAINT ck_space_translations_location_not_blank CHECK (btrim(location_text) <> ''),
    CONSTRAINT ck_space_translations_operator_not_blank
        CHECK (operator_text IS NULL OR btrim(operator_text) <> ''),
    CONSTRAINT ck_space_translations_hours_not_blank
        CHECK (hours_text IS NULL OR btrim(hours_text) <> ''),
    CONSTRAINT ck_space_translations_description_not_blank
        CHECK (description_text IS NULL OR btrim(description_text) <> ''),
    CONSTRAINT ck_space_translations_experience_not_blank
        CHECK (experience_text IS NULL OR btrim(experience_text) <> ''),
    CONSTRAINT ck_space_translations_contact_together
        CHECK ((contact_label IS NULL) = (contact_url IS NULL)),
    CONSTRAINT ck_space_translations_contact_label_not_blank
        CHECK (contact_label IS NULL OR btrim(contact_label) <> ''),
    CONSTRAINT ck_space_translations_contact_url_https
        CHECK (contact_url IS NULL OR contact_url ~ '^https://')
);

CREATE TABLE space_sort_orders (
    festival_revision_id UUID NOT NULL,
    locale VARCHAR(16) NOT NULL,
    space_id TEXT NOT NULL,
    sort_rank INTEGER NOT NULL,
    CONSTRAINT pk_space_sort_orders PRIMARY KEY (festival_revision_id, locale, space_id),
    CONSTRAINT uq_space_sort_orders_rank UNIQUE (festival_revision_id, locale, sort_rank),
    CONSTRAINT fk_space_sort_orders_space
        FOREIGN KEY (festival_revision_id, space_id)
        REFERENCES spaces(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_space_sort_orders_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_space_sort_orders_rank_positive CHECK (sort_rank > 0)
);

CREATE TABLE space_events (
    festival_revision_id UUID NOT NULL,
    space_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    sort_order INTEGER NOT NULL,
    content TEXT NOT NULL,
    CONSTRAINT pk_space_events PRIMARY KEY (festival_revision_id, space_id, locale, sort_order),
    CONSTRAINT fk_space_events_space
        FOREIGN KEY (festival_revision_id, space_id)
        REFERENCES spaces(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_space_events_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_space_events_order_positive CHECK (sort_order > 0),
    CONSTRAINT ck_space_events_content_not_blank CHECK (btrim(content) <> '')
);

CREATE TABLE space_menu_items (
    festival_revision_id UUID NOT NULL,
    space_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    sort_order INTEGER NOT NULL,
    name TEXT NOT NULL,
    price_amount INTEGER NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'KRW',
    CONSTRAINT pk_space_menu_items PRIMARY KEY (festival_revision_id, space_id, locale, sort_order),
    CONSTRAINT fk_space_menu_items_space
        FOREIGN KEY (festival_revision_id, space_id)
        REFERENCES spaces(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_space_menu_items_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_space_menu_items_order_positive CHECK (sort_order > 0),
    CONSTRAINT ck_space_menu_items_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_space_menu_items_price CHECK (price_amount >= 0),
    CONSTRAINT ck_space_menu_items_currency CHECK (currency = 'KRW')
);

CREATE TABLE places (
    festival_revision_id UUID NOT NULL,
    id TEXT NOT NULL,
    kind TEXT NOT NULL,
    space_id TEXT NULL,
    CONSTRAINT pk_places PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT uq_places_space UNIQUE (festival_revision_id, space_id),
    CONSTRAINT uq_places_id_space UNIQUE (festival_revision_id, id, space_id),
    CONSTRAINT fk_places_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_places_space
        FOREIGN KEY (festival_revision_id, space_id)
        REFERENCES spaces(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_places_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_places_kind CHECK (kind IN ('SPACE', 'FACILITY', 'LANDMARK')),
    CONSTRAINT ck_places_space_relation CHECK (
        (kind = 'SPACE' AND space_id IS NOT NULL)
        OR (kind IN ('FACILITY', 'LANDMARK') AND space_id IS NULL)
    )
);

CREATE TABLE place_translations (
    festival_revision_id UUID NOT NULL,
    place_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    name TEXT NULL,
    location_text TEXT NULL,
    hours_text TEXT NULL,
    description_text TEXT NULL,
    usage_text TEXT NULL,
    CONSTRAINT pk_place_translations PRIMARY KEY (festival_revision_id, place_id, locale),
    CONSTRAINT fk_place_translations_place
        FOREIGN KEY (festival_revision_id, place_id)
        REFERENCES places(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_place_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_place_translations_name_not_blank CHECK (name IS NULL OR btrim(name) <> ''),
    CONSTRAINT ck_place_translations_location_not_blank
        CHECK (location_text IS NULL OR btrim(location_text) <> ''),
    CONSTRAINT ck_place_translations_hours_not_blank CHECK (hours_text IS NULL OR btrim(hours_text) <> ''),
    CONSTRAINT ck_place_translations_description_not_blank
        CHECK (description_text IS NULL OR btrim(description_text) <> ''),
    CONSTRAINT ck_place_translations_usage_not_blank CHECK (usage_text IS NULL OR btrim(usage_text) <> '')
);

CREATE TABLE maps (
    festival_revision_id UUID NOT NULL,
    id TEXT NOT NULL,
    kind TEXT NOT NULL,
    sort_rank INTEGER NOT NULL,
    current_version TEXT NOT NULL,
    CONSTRAINT pk_maps PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT uq_maps_current_version UNIQUE (festival_revision_id, id, current_version),
    CONSTRAINT fk_maps_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_maps_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_maps_kind CHECK (kind IN ('OVERVIEW', 'AREA')),
    CONSTRAINT ck_maps_sort_rank_positive CHECK (sort_rank > 0),
    CONSTRAINT ck_maps_current_version_not_blank CHECK (btrim(current_version) <> '')
);

CREATE TABLE map_translations (
    festival_revision_id UUID NOT NULL,
    map_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT pk_map_translations PRIMARY KEY (festival_revision_id, map_id, locale),
    CONSTRAINT fk_map_translations_map
        FOREIGN KEY (festival_revision_id, map_id)
        REFERENCES maps(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_map_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_map_translations_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE map_asset_versions (
    festival_revision_id UUID NOT NULL,
    map_id TEXT NOT NULL,
    version TEXT NOT NULL,
    image_url TEXT NOT NULL,
    image_alt TEXT NOT NULL,
    image_width INTEGER NOT NULL,
    image_height INTEGER NOT NULL,
    CONSTRAINT pk_map_asset_versions PRIMARY KEY (festival_revision_id, map_id, version),
    CONSTRAINT fk_map_asset_versions_map
        FOREIGN KEY (festival_revision_id, map_id)
        REFERENCES maps(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_map_asset_versions_version_not_blank CHECK (btrim(version) <> ''),
    CONSTRAINT ck_map_asset_versions_url_not_blank CHECK (btrim(image_url) <> ''),
    CONSTRAINT ck_map_asset_versions_alt_not_blank CHECK (btrim(image_alt) <> ''),
    CONSTRAINT ck_map_asset_versions_dimensions CHECK (image_width > 0 AND image_height > 0)
);

ALTER TABLE maps
    ADD CONSTRAINT fk_maps_current_asset
    FOREIGN KEY (festival_revision_id, id, current_version)
    REFERENCES map_asset_versions(festival_revision_id, map_id, version)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE map_areas (
    festival_revision_id UUID NOT NULL,
    id TEXT NOT NULL,
    target_map_id TEXT NOT NULL,
    CONSTRAINT pk_map_areas PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT fk_map_areas_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_map_areas_target_map
        FOREIGN KEY (festival_revision_id, target_map_id)
        REFERENCES maps(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_map_areas_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_map_areas_target_not_blank CHECK (btrim(target_map_id) <> '')
);

CREATE TABLE map_pins (
    festival_revision_id UUID NOT NULL,
    map_id TEXT NOT NULL,
    map_version TEXT NOT NULL,
    id TEXT NOT NULL,
    category TEXT NOT NULL,
    x NUMERIC(9, 8) NOT NULL,
    y NUMERIC(9, 8) NOT NULL,
    place_id TEXT NULL,
    area_id TEXT NULL,
    CONSTRAINT pk_map_pins PRIMARY KEY (festival_revision_id, map_id, map_version, id),
    CONSTRAINT uq_map_pins_place_target
        UNIQUE (festival_revision_id, map_id, map_version, id, place_id),
    CONSTRAINT fk_map_pins_asset
        FOREIGN KEY (festival_revision_id, map_id, map_version)
        REFERENCES map_asset_versions(festival_revision_id, map_id, version) ON DELETE RESTRICT,
    CONSTRAINT fk_map_pins_place
        FOREIGN KEY (festival_revision_id, place_id)
        REFERENCES places(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_map_pins_area
        FOREIGN KEY (festival_revision_id, area_id)
        REFERENCES map_areas(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_map_pins_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_map_pins_category_not_blank CHECK (btrim(category) <> ''),
    CONSTRAINT ck_map_pins_coordinates CHECK (x >= 0 AND x <= 1 AND y >= 0 AND y <= 1),
    CONSTRAINT ck_map_pins_exactly_one_target CHECK (
        (place_id IS NOT NULL AND area_id IS NULL)
        OR (place_id IS NULL AND area_id IS NOT NULL)
    )
);

CREATE TABLE map_pin_translations (
    festival_revision_id UUID NOT NULL,
    map_id TEXT NOT NULL,
    map_version TEXT NOT NULL,
    pin_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    label TEXT NOT NULL,
    CONSTRAINT pk_map_pin_translations
        PRIMARY KEY (festival_revision_id, map_id, map_version, pin_id, locale),
    CONSTRAINT fk_map_pin_translations_pin
        FOREIGN KEY (festival_revision_id, map_id, map_version, pin_id)
        REFERENCES map_pins(festival_revision_id, map_id, map_version, id) ON DELETE RESTRICT,
    CONSTRAINT ck_map_pin_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_map_pin_translations_label_not_blank CHECK (btrim(label) <> '')
);

CREATE TABLE space_map_targets (
    festival_revision_id UUID NOT NULL,
    space_id TEXT NOT NULL,
    map_id TEXT NOT NULL,
    map_version TEXT NOT NULL,
    pin_id TEXT NOT NULL,
    place_id TEXT NOT NULL,
    CONSTRAINT pk_space_map_targets PRIMARY KEY (festival_revision_id, space_id),
    CONSTRAINT fk_space_map_targets_space_place
        FOREIGN KEY (festival_revision_id, place_id, space_id)
        REFERENCES places(festival_revision_id, id, space_id) ON DELETE RESTRICT,
    CONSTRAINT fk_space_map_targets_current_map
        FOREIGN KEY (festival_revision_id, map_id, map_version)
        REFERENCES maps(festival_revision_id, id, current_version) ON DELETE RESTRICT,
    CONSTRAINT fk_space_map_targets_pin
        FOREIGN KEY (festival_revision_id, map_id, map_version, pin_id, place_id)
        REFERENCES map_pins(festival_revision_id, map_id, map_version, id, place_id) ON DELETE RESTRICT,
    CONSTRAINT ck_space_map_targets_ids_not_blank CHECK (
        btrim(space_id) <> '' AND btrim(map_id) <> '' AND btrim(map_version) <> ''
        AND btrim(pin_id) <> '' AND btrim(place_id) <> ''
    )
);

-- V6 creates one and only one published revision. Make the pre-existing
-- singleton ticket guide revision-bound before adding its optional target FKs.
DO $$
DECLARE
    published_revision UUID;
BEGIN
    SELECT id INTO published_revision
    FROM festival_revisions
    WHERE state = 'published';

    IF published_revision IS NULL
       OR (SELECT count(*) FROM festival_revisions WHERE state = 'published') <> 1 THEN
        RAISE EXCEPTION 'V7 requires exactly one published festival revision';
    END IF;

    UPDATE ticket_guide
    SET festival_revision_id = published_revision
    WHERE festival_revision_id IS NULL;
END $$;

ALTER TABLE ticket_guide
    ALTER COLUMN festival_revision_id SET NOT NULL;

ALTER TABLE ticket_guide
    ADD CONSTRAINT ck_ticket_guide_map_target_together CHECK (
        (map_id IS NULL AND place_id IS NULL AND pin_id IS NULL AND map_version IS NULL)
        OR (
            map_id IS NOT NULL AND place_id IS NOT NULL AND pin_id IS NOT NULL
            AND map_version IS NOT NULL
        )
    ),
    ADD CONSTRAINT fk_ticket_guide_current_map
        FOREIGN KEY (festival_revision_id, map_id, map_version)
        REFERENCES maps(festival_revision_id, id, current_version) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_ticket_guide_map_pin
        FOREIGN KEY (festival_revision_id, map_id, map_version, pin_id, place_id)
        REFERENCES map_pins(festival_revision_id, map_id, map_version, id, place_id) ON DELETE RESTRICT;
