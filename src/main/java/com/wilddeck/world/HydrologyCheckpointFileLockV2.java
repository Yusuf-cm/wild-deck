package com.wilddeck.world;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
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

    /**
     * Hold an entire caller-authenticated local checkpoint fleet while
     * validating it and committing one receiver. Deterministic acquisition
     * order prevents lock-order inversion between cooperating JVM writers.
     * Every path must be distinct; hard-link and symlink checks remain in
     * withLock. This is not a distributed transaction or global lease.
     */
    static <T> T withLocks(Collection<Path> files, Operation<T> operation)
            throws IOException {
        Objects.requireNonNull(files);
        Objects.requireNonNull(operation);
        var ordered = new TreeSet<Path>(Comparator.comparing(Path::toString));
        for (var file : files) {
            Path normalized = Objects.requireNonNull(file).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized))
                throw new IOException("Checkpoint file symlinks are not supported");
            Path parent = normalized.getParent();
            if (parent == null || !Files.isDirectory(parent))
                throw new IOException("Checkpoint directory missing");
            // Canonicalize parent aliases BEFORE sorting: otherwise two
            // differently spelled paths could invert a cross-process order.
            Path canonical = Files.exists(normalized) ? normalized.toRealPath()
                : parent.toRealPath().resolve(normalized.getFileName());
            if (!ordered.add(canonical))
                throw new IOException("Duplicate physical checkpoint in lock fleet");
        }
        return withLocksOrdered(new ArrayList<>(ordered), 0, operation);
    }

    private static <T> T withLocksOrdered(List<Path> files, int index,
            Operation<T> operation) throws IOException {
        if (index == files.size()) return operation.run();
        return withLock(files.get(index),
            () -> withLocksOrdered(files, index + 1, operation));
    }

    static <T> T withLock(Path file, Operation<T> operation) throws IOException {
        Objects.requireNonNull(file);
        Objects.requireNonNull(operation);
        Path target = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target))
            throw new IOException("Checkpoint file symlinks are not supported for writes");
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("Checkpoint directory missing");
        // Atomic replacement of a multiply-linked inode would silently split
        // checkpoint aliases. Reject them where the filesystem exposes nlink.
        if (Files.exists(target)) {
            try {
                Object links = Files.getAttribute(target, "unix:nlink");
                if (links instanceof Number && ((Number) links).longValue() > 1)
                    throw new IOException("Hard-linked checkpoint files are not supported");
            } catch (UnsupportedOperationException ignored) {
                // Non-Unix providers may not expose link counts.
            }
        }
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
