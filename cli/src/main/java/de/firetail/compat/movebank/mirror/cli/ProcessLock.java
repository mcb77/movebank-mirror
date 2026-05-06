package de.firetail.compat.movebank.mirror.cli;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Single-process lock for a mirror directory. Two simultaneous {@code eventdata}
 * runs against the same dir would race on per-(tag,sensor) state files; this
 * blocks the second runner with a clear error.
 */
final class ProcessLock implements AutoCloseable {

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final FileLock lock;
    private final Path lockPath;

    private ProcessLock(Path lockPath, RandomAccessFile raf, FileChannel channel, FileLock lock) {
        this.lockPath = lockPath;
        this.raf = raf;
        this.channel = channel;
        this.lock = lock;
    }

    /** Acquires the lock or throws {@link AlreadyHeldException} if another process holds it. */
    static ProcessLock acquire(Path mirrorDir) throws IOException {
        Files.createDirectories(mirrorDir);
        Path lockPath = mirrorDir.resolve(".lock");
        @SuppressWarnings("resource") // closed by caller via close() below on failure paths
        RandomAccessFile raf = new RandomAccessFile(lockPath.toFile(), "rw");
        FileChannel channel = raf.getChannel();
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            channel.close();
            raf.close();
            throw new AlreadyHeldException(lockPath);
        }
        if (lock == null) {
            channel.close();
            raf.close();
            throw new AlreadyHeldException(lockPath);
        }
        return new ProcessLock(lockPath, raf, channel, lock);
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock != null && lock.isValid()) lock.release();
        } finally {
            try { channel.close(); } finally { raf.close(); }
        }
    }

    static final class AlreadyHeldException extends IOException {
        AlreadyHeldException(Path lockPath) {
            super("Another movebank-mirror process holds the lock at " + lockPath);
        }
    }
}
