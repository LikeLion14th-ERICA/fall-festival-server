CREATE TABLE artists (
    festival_revision_id UUID NOT NULL,
    id TEXT NOT NULL,
    category TEXT NOT NULL,
    image_url TEXT NOT NULL,
    image_width INTEGER NOT NULL,
    image_height INTEGER NOT NULL,
    CONSTRAINT pk_artists PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT fk_artists_revision
        FOREIGN KEY (festival_revision_id)
        REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_artists_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_artists_category CHECK (category IN ('ARTIST', 'CONTEST')),
    CONSTRAINT ck_artists_image_url_not_blank CHECK (btrim(image_url) <> ''),
    CONSTRAINT ck_artists_image_dimensions CHECK (image_width > 0 AND image_height > 0)
);

CREATE TABLE artist_translations (
    festival_revision_id UUID NOT NULL,
    artist_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    name TEXT NOT NULL,
    image_alt TEXT NOT NULL,
    introduction TEXT NULL,
    CONSTRAINT pk_artist_translations
        PRIMARY KEY (festival_revision_id, artist_id, locale),
    CONSTRAINT fk_artist_translations_artist
        FOREIGN KEY (festival_revision_id, artist_id)
        REFERENCES artists(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_artist_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_artist_translations_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_artist_translations_image_alt_not_blank CHECK (btrim(image_alt) <> ''),
    CONSTRAINT ck_artist_translations_introduction_not_blank
        CHECK (introduction IS NULL OR btrim(introduction) <> '')
);

CREATE TABLE artist_links (
    festival_revision_id UUID NOT NULL,
    artist_id TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    url TEXT NOT NULL,
    CONSTRAINT pk_artist_links
        PRIMARY KEY (festival_revision_id, artist_id, sort_order),
    CONSTRAINT fk_artist_links_artist
        FOREIGN KEY (festival_revision_id, artist_id)
        REFERENCES artists(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_artist_links_order_positive CHECK (sort_order > 0),
    CONSTRAINT ck_artist_links_url_https CHECK (url ~ '^https://[^[:space:]]+$')
);

CREATE TABLE artist_link_translations (
    festival_revision_id UUID NOT NULL,
    artist_id TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    locale VARCHAR(16) NOT NULL,
    label TEXT NOT NULL,
    CONSTRAINT pk_artist_link_translations
        PRIMARY KEY (festival_revision_id, artist_id, sort_order, locale),
    CONSTRAINT fk_artist_link_translations_link
        FOREIGN KEY (festival_revision_id, artist_id, sort_order)
        REFERENCES artist_links(festival_revision_id, artist_id, sort_order) ON DELETE RESTRICT,
    CONSTRAINT ck_artist_link_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_artist_link_translations_label_not_blank CHECK (btrim(label) <> '')
);

CREATE TABLE artist_songs (
    festival_revision_id UUID NOT NULL,
    artist_id TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    url TEXT NOT NULL,
    CONSTRAINT pk_artist_songs
        PRIMARY KEY (festival_revision_id, artist_id, sort_order),
    CONSTRAINT fk_artist_songs_artist
        FOREIGN KEY (festival_revision_id, artist_id)
        REFERENCES artists(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_artist_songs_order CHECK (sort_order BETWEEN 1 AND 3),
    CONSTRAINT ck_artist_songs_url_https CHECK (url ~ '^https://[^[:space:]]+$')
);

CREATE TABLE artist_song_translations (
    festival_revision_id UUID NOT NULL,
    artist_id TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    locale VARCHAR(16) NOT NULL,
    title TEXT NOT NULL,
    CONSTRAINT pk_artist_song_translations
        PRIMARY KEY (festival_revision_id, artist_id, sort_order, locale),
    CONSTRAINT fk_artist_song_translations_song
        FOREIGN KEY (festival_revision_id, artist_id, sort_order)
        REFERENCES artist_songs(festival_revision_id, artist_id, sort_order) ON DELETE RESTRICT,
    CONSTRAINT ck_artist_song_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_artist_song_translations_title_not_blank CHECK (btrim(title) <> '')
);

CREATE TABLE performances (
    festival_revision_id UUID NOT NULL,
    id TEXT NOT NULL,
    festival_date DATE NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_performances PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT fk_performances_festival_day
        FOREIGN KEY (festival_revision_id, festival_date)
        REFERENCES festival_days(festival_revision_id, festival_date) ON DELETE RESTRICT,
    CONSTRAINT ck_performances_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_performances_time_order CHECK (starts_at < ends_at),
    CONSTRAINT ck_performances_kst_date
        CHECK ((starts_at AT TIME ZONE 'Asia/Seoul')::DATE = festival_date)
);

CREATE TABLE performance_translations (
    festival_revision_id UUID NOT NULL,
    performance_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    title TEXT NOT NULL,
    description TEXT NULL,
    CONSTRAINT pk_performance_translations
        PRIMARY KEY (festival_revision_id, performance_id, locale),
    CONSTRAINT fk_performance_translations_performance
        FOREIGN KEY (festival_revision_id, performance_id)
        REFERENCES performances(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_performance_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_performance_translations_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_performance_translations_description_not_blank
        CHECK (description IS NULL OR btrim(description) <> '')
);

CREATE TABLE performance_artists (
    festival_revision_id UUID NOT NULL,
    performance_id TEXT NOT NULL,
    artist_id TEXT NOT NULL,
    display_order INTEGER NOT NULL,
    CONSTRAINT pk_performance_artists
        PRIMARY KEY (festival_revision_id, performance_id, artist_id),
    CONSTRAINT uq_performance_artists_display_order
        UNIQUE (festival_revision_id, performance_id, display_order),
    CONSTRAINT fk_performance_artists_performance
        FOREIGN KEY (festival_revision_id, performance_id)
        REFERENCES performances(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_performance_artists_artist
        FOREIGN KEY (festival_revision_id, artist_id)
        REFERENCES artists(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_performance_artists_order_positive CHECK (display_order > 0)
);

CREATE TABLE timetable_configs (
    festival_revision_id UUID NOT NULL,
    axis_start_time TIME WITHOUT TIME ZONE NOT NULL,
    axis_end_time TIME WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_timetable_configs PRIMARY KEY (festival_revision_id),
    CONSTRAINT fk_timetable_configs_revision
        FOREIGN KEY (festival_revision_id)
        REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_timetable_configs_time_order CHECK (axis_start_time < axis_end_time)
);

CREATE TABLE prohibited_items (
    festival_revision_id UUID NOT NULL,
    id TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT pk_prohibited_items PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT uq_prohibited_items_sort_order
        UNIQUE (festival_revision_id, sort_order),
    CONSTRAINT fk_prohibited_items_revision
        FOREIGN KEY (festival_revision_id)
        REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_prohibited_items_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_prohibited_items_order_positive CHECK (sort_order > 0)
);

CREATE TABLE prohibited_item_translations (
    festival_revision_id UUID NOT NULL,
    item_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    label TEXT NOT NULL,
    CONSTRAINT pk_prohibited_item_translations
        PRIMARY KEY (festival_revision_id, item_id, locale),
    CONSTRAINT fk_prohibited_item_translations_item
        FOREIGN KEY (festival_revision_id, item_id)
        REFERENCES prohibited_items(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_prohibited_item_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_prohibited_item_translations_label_not_blank CHECK (btrim(label) <> '')
);

CREATE TABLE prohibited_messages (
    festival_revision_id UUID NOT NULL,
    locale VARCHAR(16) NOT NULL,
    message TEXT NOT NULL,
    CONSTRAINT pk_prohibited_messages PRIMARY KEY (festival_revision_id, locale),
    CONSTRAINT fk_prohibited_messages_revision
        FOREIGN KEY (festival_revision_id)
        REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_prohibited_messages_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_prohibited_messages_message_not_blank CHECK (btrim(message) <> '')
);
