package de.firetail.compat.movebank.mirror.cli;

import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Global options shared by every subcommand. Mixed into the root {@link CliMain},
 * with {@link ScopeType#INHERIT} so users may place them on either side of the
 * subcommand: {@code movebank-mirror -u alice metadata} or
 * {@code movebank-mirror metadata -u alice}.
 *
 * <p>Subcommands access the parsed values via {@code @ParentCommand CliMain parent}
 * and read {@code parent.globals}.
 */
public class GlobalOptions {

    @Option(names = {"-u", "--user"}, paramLabel = "USER", scope = ScopeType.INHERIT,
            defaultValue = "${env:MOVEBANK_USER}",
            description = "Movebank username (env: MOVEBANK_USER).")
    String user;

    @Option(names = {"-p", "--password"}, paramLabel = "PASSWORD", scope = ScopeType.INHERIT,
            defaultValue = "${env:MOVEBANK_PASSWORD}",
            description = "Movebank password — discouraged on the command line "
                    + "(visible to other users via ps). Prefer env: MOVEBANK_PASSWORD, "
                    + "--password-file, or --password-stdin.")
    String password;

    @Option(names = "--password-file", paramLabel = "FILE", scope = ScopeType.INHERIT,
            description = "Read the password from the first line of FILE.")
    Path passwordFile;

    @Option(names = "--password-stdin", scope = ScopeType.INHERIT,
            description = "Read the password from stdin (first line).")
    boolean passwordStdin;

    @Option(names = "--base-url", paramLabel = "URL", scope = ScopeType.INHERIT,
            defaultValue = "${env:MOVEBANK_BASE_URL:-https://www.movebank.org/movebank}",
            description = "Movebank REST API base URL (default: ${DEFAULT-VALUE}).")
    String baseUrl;

    @Option(names = {"-d", "--mirror-dir"}, paramLabel = "DIR", scope = ScopeType.INHERIT,
            defaultValue = "${env:MOVEBANK_MIRROR_DIR:-./movebank-mirror}",
            description = "Local directory the mirror reads/writes (default: ${DEFAULT-VALUE}).")
    Path mirrorDir;

    @Option(names = {"-v", "--verbose"}, scope = ScopeType.INHERIT,
            description = "Increase log verbosity. -v = debug, -vv = trace.")
    boolean[] verbosity = new boolean[0];

    @Option(names = {"-q", "--quiet"}, scope = ScopeType.INHERIT,
            description = "Reduce log verbosity to warnings only.")
    boolean quiet;

    /** Resolves the password from --password / --password-file / --password-stdin / env. */
    String resolvePassword() throws IOException {
        if (password != null && !password.isEmpty()) return password;
        if (passwordFile != null) {
            String content = Files.readString(passwordFile, StandardCharsets.UTF_8);
            return firstLine(content);
        }
        if (passwordStdin) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line == null) {
                    throw new IOException("--password-stdin requested but stdin was empty");
                }
                return line;
            }
        }
        return null;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        return line;
    }

    /** Pushes log level into slf4j-simple via system properties. Call before any logging. */
    void applyLogLevel() {
        String level;
        if (quiet) {
            level = "warn";
        } else if (verbosity.length >= 2) {
            level = "trace";
        } else if (verbosity.length == 1) {
            level = "debug";
        } else {
            level = "info";
        }
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level);
    }
}
