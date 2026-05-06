package de.firetail.compat.movebank.mirror;

import org.opentest4j.TestAbortedException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared helpers for integration tests. */
public class MirrorTestSupport {

    public static final String MOVEBANK_BASE_URL = "https://www.movebank.org/movebank";

    private MirrorTestSupport() {}

    /** Returns Movebank credentials from env vars, skipping the test if either is missing. */
    public static Credentials requireCredentials() {
        String user = System.getenv("MOVEBANK_USER");
        String password = System.getenv("MOVEBANK_PASSWORD");
        if (user == null || user.isBlank()) {
            abort("MOVEBANK_USER environment variable is not set — skipping integration test");
        }
        if (password == null || password.isBlank()) {
            abort("MOVEBANK_PASSWORD environment variable is not set — skipping integration test");
        }
        return new Credentials(user, password);
    }

    private static void abort(String message) {
        // Echo to stdout so the reason is visible in IntelliJ's Gradle Run panel and
        // CI logs — Gradle's default test-event stream does not surface skip messages.
        System.out.println("[INTEGRATION TEST SKIPPED] " + message);
        throw new TestAbortedException(message);
    }

    /** Creates a fresh tmp directory under build/ for an integration test run. */
    public static File freshMirrorDir(String label) throws Exception {
        Path build = new File("build/integration-tmp/" + label).toPath();
        Files.createDirectories(build);
        return build.toFile();
    }

    public record Credentials(String user, String password) {}
}
