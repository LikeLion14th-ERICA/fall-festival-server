-- Goods is festival_id-scoped dynamic operational data (not revision-scoped),
-- matching notice (V17), crowding (V16) and operational account settings (V15).
-- Same manual ko/en-required translation rule as notice: no automatic
-- translation, zh-Hans/ja are best-effort with contentLocale fallback.
-- Deletion is hard delete (confirmed by the team; unlike notice, there is no
-- soft-delete/trash for goods), so there is no deleted_at column here.
-- Payment account details are not stored here: PaymentGuide reads
-- operational_account_settings (purpose = 'GOODS') read-only, since account
-- changes are intentionally impossible from the admin web page.
-- Images and the goods payment guide's operational instructions/location/hours
-- text are deferred to a later migration once the media upload subsystem
-- exists to back them.
CREATE TABLE goods (
    id UUID NOT NULL,
    festival_id UUID NOT NULL REFERENCES festivals(id) ON DELETE RESTRICT,
    option_mode VARCHAR(16) NOT NULL,
    price_amount BIGINT NOT NULL,
    price_currency VARCHAR(8) NOT NULL DEFAULT 'KRW',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_goods PRIMARY KEY (id),
    CONSTRAINT ck_goods_option_mode CHECK (option_mode IN ('SINGLE', 'OPTIONS')),
    CONSTRAINT ck_goods_price_amount CHECK (price_amount >= 0),
    CONSTRAINT ck_goods_price_currency CHECK (price_currency = 'KRW')
);

CREATE INDEX ix_goods_festival ON goods (festival_id, created_at DESC);

CREATE TABLE goods_translations (
    goods_id UUID NOT NULL REFERENCES goods(id) ON DELETE CASCADE,
    locale VARCHAR(16) NOT NULL,
    name TEXT NOT NULL,
    description TEXT NULL,
    CONSTRAINT pk_goods_translations PRIMARY KEY (goods_id, locale),
    CONSTRAINT ck_goods_translations_locale CHECK (locale IN ('ko', 'en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_goods_translations_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_goods_translations_description_not_blank CHECK (description IS NULL OR btrim(description) <> '')
);

CREATE TABLE goods_colors (
    id UUID NOT NULL,
    goods_id UUID NOT NULL REFERENCES goods(id) ON DELETE CASCADE,
    sort_order INT NOT NULL,
    CONSTRAINT pk_goods_colors PRIMARY KEY (id),
    CONSTRAINT uq_goods_colors_sort_order UNIQUE (goods_id, sort_order)
);

CREATE TABLE goods_color_translations (
    color_id UUID NOT NULL REFERENCES goods_colors(id) ON DELETE CASCADE,
    locale VARCHAR(16) NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT pk_goods_color_translations PRIMARY KEY (color_id, locale),
    CONSTRAINT ck_goods_color_translations_locale CHECK (locale IN ('ko', 'en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_goods_color_translations_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE goods_sizes (
    id UUID NOT NULL,
    goods_id UUID NOT NULL REFERENCES goods(id) ON DELETE CASCADE,
    sort_order INT NOT NULL,
    CONSTRAINT pk_goods_sizes PRIMARY KEY (id),
    CONSTRAINT uq_goods_sizes_sort_order UNIQUE (goods_id, sort_order)
);

CREATE TABLE goods_size_translations (
    size_id UUID NOT NULL REFERENCES goods_sizes(id) ON DELETE CASCADE,
    locale VARCHAR(16) NOT NULL,
    label TEXT NOT NULL,
    CONSTRAINT pk_goods_size_translations PRIMARY KEY (size_id, locale),
    CONSTRAINT ck_goods_size_translations_locale CHECK (locale IN ('ko', 'en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_goods_size_translations_label_not_blank CHECK (btrim(label) <> '')
);

-- SINGLE mode: exactly one combination row per goods, color_id and size_id
-- both null (an opaque single sale state, no real color/size rows exist).
-- OPTIONS mode: color_id and size_id both set, one row per actually offered
-- combination (no auto cross-product).
CREATE TABLE goods_combinations (
    id UUID NOT NULL,
    goods_id UUID NOT NULL REFERENCES goods(id) ON DELETE CASCADE,
    color_id UUID NULL REFERENCES goods_colors(id) ON DELETE CASCADE,
    size_id UUID NULL REFERENCES goods_sizes(id) ON DELETE CASCADE,
    availability VARCHAR(16) NOT NULL DEFAULT 'ON_SALE',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_goods_combinations PRIMARY KEY (id),
    CONSTRAINT ck_goods_combinations_availability CHECK (availability IN ('ON_SALE', 'SOLD_OUT')),
    CONSTRAINT ck_goods_combinations_color_size_paired
        CHECK ((color_id IS NULL) = (size_id IS NULL)),
    CONSTRAINT uq_goods_combinations_color_size UNIQUE (goods_id, color_id, size_id)
);

-- The plain UNIQUE constraint above does not stop multiple SINGLE-mode rows
-- (Postgres treats each NULL pair as distinct), so a partial index enforces
-- the "exactly one" rule for that case specifically.
CREATE UNIQUE INDEX uq_goods_combinations_single
    ON goods_combinations (goods_id)
    WHERE color_id IS NULL AND size_id IS NULL;

CREATE INDEX ix_goods_combinations_color ON goods_combinations (color_id);
CREATE INDEX ix_goods_combinations_size ON goods_combinations (size_id);
