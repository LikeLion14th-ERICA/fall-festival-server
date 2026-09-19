package dev.espero.festival.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Local filesystem storage constrained to one configured root. */
public final class FileSystemMediaStorage implements MediaStorage {

    private static final Set<String> REQUIRED_VARIANTS = Arrays.stream(MediaVariant.values())
        .map(MediaVariant::filename)
        .collect(Collectors.toUnmodifiableSet());

    private final Path root;
    private final Path stagingRoot;

    public FileSystemMediaStorage(Path configuredRoot) throws IOException {
        Objects.requireNonNull(configuredRoot, "Media storage root is required");
        Path normalizedRoot = configuredRoot.toAbsolutePath().normalize();
        rejectExistingSymlinkComponents(normalizedRoot);
        Files.createDirectories(normalizedRoot);
        rejectExistingSymlinkComponents(normalizedRoot);
        this.root = normalizedRoot;
        this.stagingRoot = checked(normalizedRoot.resolve(".staging"));
        Files.createDirectories(stagingRoot);
        rejectExistingSymlinkComponents(stagingRoot);
    }

    @Override
    public void createStaging(UUID operationId) throws IOException {
        Path staging = stagingDirectory(operationId);
        rejectExistingSymlinkComponents(staging.getParent());
        Files.createDirectory(staging);
    }

    @Override
    public OutputStream openStagingOutput(UUID operationId, MediaVariant variant) throws IOException {
        Objects.requireNonNull(variant, "Media variant is required");
        Path staging = stagingDirectory(operationId);
        requireDirectoryWithoutSymlink(staging);
        Path output = checked(staging.resolve(variant.filename()));
        return Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public void discardStaging(UUID operationId) throws IOException {
        Path staging = stagingDirectory(operationId);
        if (Files.notExists(staging, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        rejectExistingSymlinkComponents(staging);
        deleteTree(staging);
    }

    @Override
    public String storageKey(UUID festivalId, UUID mediaId) {
        requireUuid(festivalId, "Festival id");
        requireUuid(mediaId, "Media id");
        return "goods/" + festivalId + "/" + shard(mediaId) + "/" + mediaId;
    }

    @Override
    public void finalizeStaging(UUID operationId, UUID festivalId, UUID mediaId) throws IOException {
        Path staging = stagingDirectory(operationId);
        requireCompleteStaging(staging);

        Path target = finalDirectory(festivalId, mediaId);
        Path parent = target.getParent();
        rejectExistingSymlinkComponents(parent);
        Files.createDirectories(parent);
        rejectExistingSymlinkComponents(parent);

        Path lock = checked(parent.resolve("." + mediaId + ".finalize.lock"));
        Files.createFile(lock);
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw exception;
            }
        } finally {
            Files.deleteIfExists(lock);
        }
    }

    @Override
    public InputStream open(UUID festivalId, UUID mediaId, MediaVariant variant) throws IOException {
        Objects.requireNonNull(variant, "Media variant is required");
        Path input = checked(finalDirectory(festivalId, mediaId).resolve(variant.filename()));
        rejectExistingSymlinkComponents(input);
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(input.toString());
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        return Channels.newInputStream(Files.newByteChannel(input, options));
    }

    @Override
    public void delete(UUID festivalId, UUID mediaId) throws IOException {
        Path target = finalDirectory(festivalId, mediaId);
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        rejectExistingSymlinkComponents(target);
        deleteTree(target);
    }

    private void deleteTree(Path target) throws IOException {
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    Path stagingDirectory(UUID operationId) {
        requireUuid(operationId, "Operation id");
        return checked(stagingRoot.resolve(operationId.toString()));
    }

    Path finalDirectory(UUID festivalId, UUID mediaId) {
        requireUuid(festivalId, "Festival id");
        requireUuid(mediaId, "Media id");
        return checked(root.resolve("goods")
            .resolve(festivalId.toString())
            .resolve(shard(mediaId))
            .resolve(mediaId.toString()));
    }

    private void requireCompleteStaging(Path staging) throws IOException {
        requireDirectoryWithoutSymlink(staging);
        Set<String> entries;
        try (var files = Files.list(staging)) {
            entries = files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
        if (!entries.equals(REQUIRED_VARIANTS)) {
            throw new IOException("Staging must contain every normalized media variant and no other entries");
        }
        for (MediaVariant variant : MediaVariant.values()) {
            Path file = checked(staging.resolve(variant.filename()));
            rejectExistingSymlinkComponents(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Staging media variant is not a regular file");
            }
        }
    }

    private void requireDirectoryWithoutSymlink(Path directory) throws IOException {
        rejectExistingSymlinkComponents(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(directory.toString());
        }
    }

    private Path checked(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new SecurityException("Media path escapes the configured storage root");
        }
        return normalized;
    }

    private static void rejectExistingSymlinkComponents(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null ? component : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not allowed in media storage paths");
            }
        }
    }

    private static String shard(UUID mediaId) {
        return mediaId.toString().replace("-", "").substring(0, 2);
    }

    private static void requireUuid(UUID value, String label) {
        Objects.requireNonNull(value, label + " is required");
    }
}
