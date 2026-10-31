package com.wilddeck.world;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Serializes read-check-replace checkpoint transactions on a local filesystem.
 * The sidecar lock survives atomic replacement of the checkpoint itself.
 * All checkpoint writers must cooperate; this is not a distributed lease.
 */
final class HydrologyCheckpointFileLockV2 {
    private HydrologyCheckpointFileLockV2() {}
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    @FunctionalInterface
    interface Operation<T> { T run() throws IOException; }

    static <T> T withLock(Path file, Operation<T> operation) throws IOException {
        Objects.requireNonNull(file);
        Objects.requireNonNull(operation);
        Path target = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target))
            throw new IOException("Checkpoint file symlinks are not supported for writes");
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("Checkpoint directory missing");
        // Canonicalize existing symlinks and the parent of not-yet-created files.
        target = Files.exists(target) ? target.toRealPath()
            : parent.toRealPath().resolve(target.getFileName());
        Path lock = target.resolveSibling("." + target.getFileName() + ".hydrology.lock");
        synchronized (JVM_LOCKS.computeIfAbsent(lock, ignored -> new Object())) {
            try (var channel = FileChannel.open(lock, CREATE, WRITE);
                 var held = channel.lock()) {
                return operation.run();
            }
        }
    }
}
