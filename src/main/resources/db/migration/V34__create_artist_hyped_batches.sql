-- Request identity only: no account, device, IP or other personal identifier.
-- Keep successful receipts for the festival lifetime so delayed retries cannot count twice.
CREATE TABLE artist_hyped_batches (
    festival_id UUID NOT NULL REFERENCES festivals(id) ON DELETE CASCADE,
    batch_id UUID NOT NULL,
    artist_id TEXT NOT NULL CHECK (btrim(artist_id) <> ''),
    delta INTEGER NOT NULL CHECK (delta BETWEEN 1 AND 20),
    count_prefix TEXT NOT NULL,
    hyped_count BIGINT CHECK (hyped_count >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (festival_id, batch_id)
);
