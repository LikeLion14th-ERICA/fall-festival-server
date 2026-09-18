-- Home links served by GET /api/v2/config (HOME-004, HOME-005, HOME-008,
-- WELCOME-001). They belong to a festival revision like the rest of the
-- catalog, so a publish or rollback changes them together with it.

CREATE TABLE festival_links (
    festival_revision_id UUID NOT NULL,
    id TEXT NOT NULL,
    kind TEXT NOT NULL,
    url TEXT NOT NULL,
    icon_key TEXT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT pk_festival_links PRIMARY KEY (festival_revision_id, id),
    CONSTRAINT fk_festival_links_revision
        FOREIGN KEY (festival_revision_id) REFERENCES festival_revisions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_festival_links_id_not_blank CHECK (btrim(id) <> ''),
    CONSTRAINT ck_festival_links_kind
        CHECK (kind IN ('UNIVERSITY_NOTICES', 'FAQ', 'OFFICIAL_CHANNEL', 'WELCOME_DAY')),
    CONSTRAINT ck_festival_links_https CHECK (url LIKE 'https://%'),
    CONSTRAINT ck_festival_links_icon_key CHECK (
        (kind = 'OFFICIAL_CHANNEL' AND icon_key IS NOT NULL AND btrim(icon_key) <> '')
        OR (kind <> 'OFFICIAL_CHANNEL' AND icon_key IS NULL)
    ),
    CONSTRAINT ck_festival_links_sort_order_positive CHECK (sort_order > 0)
);

-- Notices, FAQ and the welcome day are single links; channels are a list.
CREATE UNIQUE INDEX uq_festival_links_single_kind
    ON festival_links (festival_revision_id, kind)
    WHERE kind <> 'OFFICIAL_CHANNEL';

CREATE UNIQUE INDEX uq_festival_links_channel_order
    ON festival_links (festival_revision_id, sort_order)
    WHERE kind = 'OFFICIAL_CHANNEL';

CREATE TABLE festival_link_translations (
    festival_revision_id UUID NOT NULL,
    link_id TEXT NOT NULL,
    locale VARCHAR(16) NOT NULL,
    label TEXT NOT NULL,
    CONSTRAINT pk_festival_link_translations PRIMARY KEY (festival_revision_id, link_id, locale),
    CONSTRAINT fk_festival_link_translations_link
        FOREIGN KEY (festival_revision_id, link_id)
        REFERENCES festival_links(festival_revision_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_festival_link_translations_locale_not_blank CHECK (btrim(locale) <> ''),
    CONSTRAINT ck_festival_link_translations_label_not_blank CHECK (btrim(label) <> '')
);
