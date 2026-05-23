package de.firetail.compat.movebank.mirror.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Reads the version from the jar manifest's Implementation-Version, set
 * by Gradle's jar task at build time from project.version. Avoids the
 * "forgot to update the @Command(version = ...) string before release"
 * trap.
 *
 * Returns "movebank-mirror (local build)" when run from an exploded
 * classes directory (no jar, no manifest) — e.g. an IDE run config or
 * `gradlew :cli:run`.
 */
public class ManifestVersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "(local build)";
        return new String[] { "movebank-mirror " + version };
    }
}
