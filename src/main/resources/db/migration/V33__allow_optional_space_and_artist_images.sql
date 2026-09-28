-- Frontend-owned artwork may be absent from the published catalog. Keep
-- dimensions paired with a real URL; existing image rows remain unchanged.
ALTER TABLE spaces
    ALTER COLUMN image_url DROP NOT NULL,
    ALTER COLUMN image_width DROP NOT NULL,
    ALTER COLUMN image_height DROP NOT NULL;

ALTER TABLE spaces DROP CONSTRAINT ck_spaces_image_dimensions;
ALTER TABLE spaces ADD CONSTRAINT ck_spaces_image_complete CHECK (
    (image_url IS NULL AND image_width IS NULL AND image_height IS NULL)
    OR (image_url IS NOT NULL AND image_width IS NOT NULL AND image_height IS NOT NULL
        AND image_width > 0 AND image_height > 0)
);

ALTER TABLE artists
    ALTER COLUMN image_url DROP NOT NULL,
    ALTER COLUMN image_width DROP NOT NULL,
    ALTER COLUMN image_height DROP NOT NULL;

ALTER TABLE artists DROP CONSTRAINT ck_artists_image_dimensions;
ALTER TABLE artists ADD CONSTRAINT ck_artists_image_complete CHECK (
    (image_url IS NULL AND image_width IS NULL AND image_height IS NULL)
    OR (image_url IS NOT NULL AND image_width IS NOT NULL AND image_height IS NOT NULL
        AND image_width > 0 AND image_height > 0)
);
