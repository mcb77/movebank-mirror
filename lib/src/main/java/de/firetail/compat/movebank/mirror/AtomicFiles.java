package de.firetail.compat.movebank.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes files via a temp sibling plus atomic rename, so a crash or a
 * concurrent reader never observes a half-written file.
 */
public final class AtomicFiles {

    private AtomicFiles() {}

    /** Serializes {@code value} as pretty-printed JSON and atomically replaces {@code target}. */
    public static void writeJson(ObjectMapper mapper, File target, Object value) throws IOException {
        Path tmp = new File(target.getParentFile(), target.getName() + ".tmp").toPath();
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            moveAtomically(tmp, target.toPath());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Renames {@code source} onto {@code target} atomically, replacing any existing file. */
    public static void moveAtomically(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
