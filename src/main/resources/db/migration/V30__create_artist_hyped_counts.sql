-- Dynamic, anonymous artist participation belongs to the festival, not a
-- published catalog revision. Re-publishing or rolling back content must not
-- reset a performer's count.
CREATE TABLE artist_hyped_counts (
    festival_id UUID NOT NULL,
    artist_id TEXT NOT NULL,
    hyped_count BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_artist_hyped_counts PRIMARY KEY (festival_id, artist_id),
    CONSTRAINT fk_artist_hyped_counts_festival
        FOREIGN KEY (festival_id) REFERENCES festivals(id) ON DELETE RESTRICT,
    CONSTRAINT ck_artist_hyped_counts_artist_id_not_blank CHECK (btrim(artist_id) <> ''),
    CONSTRAINT ck_artist_hyped_counts_nonnegative CHECK (hyped_count >= 0)
);
