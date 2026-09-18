-- Goods media is festival-scoped operational data. Files are written by the
-- application under a server-generated storage key; client filenames and
-- absolute filesystem paths are never persisted.
ALTER TABLE goods
    ADD CONSTRAINT uq_goods_id_festival UNIQUE (id, festival_id);

CREATE TABLE media_assets (
    id UUID NOT NULL,
    festival_id UUID NOT NULL REFERENCES festivals(id) ON DELETE RESTRICT,
    purpose VARCHAR(32) NOT NULL,
    storage_key TEXT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    source_size_bytes BIGINT NOT NULL,
    source_width INTEGER NOT NULL,
    source_height INTEGER NOT NULL,
    master_width INTEGER NOT NULL,
    master_height INTEGER NOT NULL,
    normalized_format VARCHAR(16) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attached_at TIMESTAMP WITH TIME ZONE NULL,
    detached_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT pk_media_assets PRIMARY KEY (id),
    CONSTRAINT uq_media_assets_id_festival UNIQUE (id, festival_id),
    CONSTRAINT uq_media_assets_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_media_assets_purpose CHECK (purpose = 'GOODS_IMAGE'),
    CONSTRAINT ck_media_assets_storage_key CHECK (
        storage_key ~ '^goods/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_media_assets_source_sha256 CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_media_assets_source_size CHECK (
        source_size_bytes > 0 AND source_size_bytes <= 10485760
    ),
    CONSTRAINT ck_media_assets_source_dimensions CHECK (
        source_width = source_height AND source_width BETWEEN 1024 AND 4096
    ),
    CONSTRAINT ck_media_assets_master_dimensions CHECK (
        master_width = master_height
        AND master_width BETWEEN 1024 AND 2048
        AND master_width <= source_width
    ),
    CONSTRAINT ck_media_assets_normalized_format CHECK (normalized_format = 'WEBP'),
    CONSTRAINT ck_media_assets_content_type CHECK (content_type = 'image/webp'),
    CONSTRAINT ck_media_assets_lifecycle CHECK (
        (detached_at IS NULL OR attached_at IS NOT NULL)
        AND (attached_at IS NULL OR attached_at >= created_at)
        AND (detached_at IS NULL OR detached_at >= attached_at)
    )
);

CREATE INDEX ix_media_assets_unattached_cleanup
    ON media_assets (created_at, id)
    WHERE attached_at IS NULL AND detached_at IS NULL;

CREATE INDEX ix_media_assets_detached_cleanup
    ON media_assets (detached_at, id)
    WHERE detached_at IS NOT NULL;

CREATE TABLE goods_images (
    media_id UUID NOT NULL,
    festival_id UUID NOT NULL,
    goods_id UUID NOT NULL,
    sort_order SMALLINT NOT NULL,
    CONSTRAINT pk_goods_images PRIMARY KEY (media_id),
    CONSTRAINT uq_goods_images_sort_order UNIQUE (goods_id, sort_order),
    CONSTRAINT ck_goods_images_sort_order CHECK (sort_order IN (0, 1)),
    CONSTRAINT fk_goods_images_media_festival
        FOREIGN KEY (media_id, festival_id)
        REFERENCES media_assets(id, festival_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_goods_images_goods_festival
        FOREIGN KEY (goods_id, festival_id)
        REFERENCES goods(id, festival_id)
        ON DELETE RESTRICT
);

CREATE TABLE goods_image_translations (
    media_id UUID NOT NULL REFERENCES goods_images(media_id) ON DELETE CASCADE,
    locale VARCHAR(16) NOT NULL,
    alt_text TEXT NOT NULL,
    CONSTRAINT pk_goods_image_translations PRIMARY KEY (media_id, locale),
    CONSTRAINT ck_goods_image_translations_locale CHECK (locale IN ('ko', 'en', 'zh-Hans', 'ja')),
    CONSTRAINT ck_goods_image_translations_alt_not_blank CHECK (btrim(alt_text) <> '')
);
