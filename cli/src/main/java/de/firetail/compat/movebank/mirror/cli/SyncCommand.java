package de.firetail.compat.movebank.mirror.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "sync",
        mixinStandardHelpOptions = true,
        description = "Run a metadata pass, then start the event-data sync. The common "
                + "'just keep this directory current' mode.")
public class SyncCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(SyncCommand.class);

    @ParentCommand CliMain parent;

    @Option(names = "--once",
            description = "Run a single event-data pass after the metadata pass and exit.")
    boolean once;

    @Option(names = "--chunk-size", paramLabel = "N",
            description = "Records per catch-up chunk. Default: 50000.")
    Integer chunkSize;

    @Option(names = "--update-sleep", paramLabel = "DURATION",
            converter = HumanDuration.class,
            description = "Sleep between event-data passes once caught up (5m, 1h30m, PT5M).")
    Duration updateSleep;

    @Option(names = "--license", paramLabel = "MODE",
            description = "License acceptance: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    MetadataCommand.LicenseMode licenseMode = MetadataCommand.LicenseMode.record;

    @Option(names = "--study", paramLabel = "ID",
            description = "Restrict both passes to specific study ids. Repeatable.")
    Set<String> includeStudies = new HashSet<>();

    @Option(names = "--skip-metadata",
            description = "Skip the metadata pass — only sync event data for already-mirrored studies.")
    boolean skipMetadata;

    @Override
    public Integer call() throws Exception {
        GlobalOptions g = parent.globals;

        if (!skipMetadata) {
            MetadataCommand md = new MetadataCommand();
            md.parent = parent;
            md.globalsOverride = g;
            md.includeStudies = includeStudies;
            md.licenseMode = licenseMode;
            int rc = md.call();
            // GENERIC_ERROR means some studies failed (e.g. 403 on one study) — already
            // logged; event data for the rest must still sync. Anything else is fatal.
            if (rc == ExitCode.GENERIC_ERROR) {
                logger.warn("Metadata pass had per-study failures — continuing with event data");
            } else if (rc != ExitCode.OK) {
                return rc;
            }
        }

        EventDataCommand ev = new EventDataCommand();
        ev.parent = parent;
        ev.globalsOverride = g;
        ev.once = once;
        ev.chunkSize = chunkSize;
        ev.updateSleep = updateSleep;
        ev.includeStudies = includeStudies;
        return ev.call();
    }
}
