-- Non-Korean text for the catalog parts that were stored in one language:
-- the festival title, map image alt text and the ticket and stamp guides.
-- Korean stays in the original rows; these tables hold en, zh-Hans and ja
-- only. They belong to a festival revision like the rest of the catalog, so
-- a publish or rollback changes them together with it. A locale is served
-- only when every one of its rows is present (see LocaleCompletenessStore).

CREATE TABLE festival_title_translations (
    festival_revision_id UUID NOT NULL,
    locale VARCHAR(16) NOT NULL,
    title TEXT NOT NULL,
    CONSTRAINT pk_festival_title_translations PRIMARY KEY (festival_revision_id, locale),
    CONSTRAINT fk_festival_title_translations_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_festival_title_translations_locale CHECK (locale IN ('en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_festival_title_translations_title_not_blank CHECK (btrim(title) <> '')
);

CREATE TABLE map_asset_translations (
    festival_revision_id UUID NOT NULL,
    map_id TEXT NOT NULL,
    version TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    image_alt TEXT NOT NULL,
    CONSTRAINT pk_map_asset_translations PRIMARY KEY (festival_revision_id, map_id, version, locale),
    CONSTRAINT fk_map_asset_translations_asset
        FOREIGN KEY (festival_revision_id, map_id, version)
        REFERENCES map_asset_versions(festival_revision_id, map_id, version) ON DELETE RESTRICT,
    CONSTRAINT ck_map_asset_translations_locale CHECK (locale IN ('en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_map_asset_translations_alt_not_blank CHECK (btrim(image_alt) <> '')
);

CREATE TABLE ticket_guide_translations (
    festival_revision_id UUID NOT NULL,
    id SMALLINT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    instructions TEXT[] NOT NULL DEFAULT '{}',
    CONSTRAINT pk_ticket_guide_translations PRIMARY KEY (festival_revision_id, id, locale),
    CONSTRAINT fk_ticket_guide_translations_guide
        FOREIGN KEY (festival_revision_id, id)
        REFERENCES ticket_guide_revisions(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_ticket_guide_translations_locale CHECK (locale IN ('en', 'zh-Hans', 'ja'))
);

CREATE TABLE stamp_guide_translations (
    festival_revision_id UUID NOT NULL,
    id SMALLINT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    title TEXT NOT NULL,
    instructions TEXT[] NOT NULL DEFAULT '{}',
    reward_name TEXT NOT NULL,
    reward_location_text TEXT NULL,
    reward_hours_text TEXT NULL,
    reward_notice TEXT NOT NULL,
    CONSTRAINT pk_stamp_guide_translations PRIMARY KEY (festival_revision_id, id, locale),
    CONSTRAINT fk_stamp_guide_translations_guide
        FOREIGN KEY (festival_revision_id, id)
        REFERENCES stamp_guide_revisions(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_stamp_guide_translations_locale CHECK (locale IN ('en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_stamp_guide_translations_text_not_blank
        CHECK (btrim(title) <> '' AND btrim(reward_name) <> '' AND btrim(reward_notice) <> '')
);
