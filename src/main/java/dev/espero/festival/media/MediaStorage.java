package dev.espero.festival.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Filesystem-independent primitives for UUID-addressed goods media. */
public interface MediaStorage {

    void createStaging(UUID operationId) throws IOException;

    OutputStream openStagingOutput(UUID operationId, MediaVariant variant) throws IOException;

    String storageKey(UUID festivalId, UUID mediaId);

    void finalizeStaging(UUID operationId, UUID festivalId, UUID mediaId) throws IOException;

    InputStream open(UUID festivalId, UUID mediaId, MediaVariant variant) throws IOException;

    void delete(UUID festivalId, UUID mediaId) throws IOException;
}
