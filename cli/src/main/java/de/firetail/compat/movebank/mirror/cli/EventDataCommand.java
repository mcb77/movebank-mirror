package de.firetail.compat.movebank.mirror.cli;

import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.mirror.eventdata.EventDataDownloader;
import de.firetail.compat.movebank.mirror.eventdata.EventDataSyncLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "eventdata",
        mixinStandardHelpOptions = true,
        description = "Sync event data for studies in --mirror-dir. Default: loop forever; "
                + "use --once for a single pass.")
public class EventDataCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(EventDataCommand.class);

    @ParentCommand CliMain parent;

    /** Lets {@link SyncCommand} inject globals when invoking us programmatically. */
    GlobalOptions globalsOverride;

    GlobalOptions globals() {
        return globalsOverride != null ? globalsOverride : parent.globals;
    }

    @Option(names = "--once",
            description = "Run a single pass and exit (suitable for cron).")
    boolean once;

    @Option(names = "--chunk-size", paramLabel = "N",
            description = "Records per catch-up chunk. Default: 50000.")
    Integer chunkSize;

    @Option(names = "--update-sleep", paramLabel = "DURATION",
            converter = HumanDuration.class,
            description = "Sleep between passes once all studies are caught up. "
                    + "Examples: 5m, 1h30m, PT5M. Default: 5m.")
    Duration updateSleep;

    @Option(names = "--study", paramLabel = "ID",
            description = "Restrict to specific study ids. Repeatable.")
    Set<String> includeStudies = new HashSet<>();

    @Override
    public Integer call() throws Exception {
        GlobalOptions g = globals();
        g.applyLogLevel();

        if (g.user == null || g.user.isBlank()) {
            System.err.println("error: --user (or env MOVEBANK_USER) is required");
            return ExitCode.USAGE_ERROR;
        }
        String password = g.resolvePassword();
        if (password == null || password.isBlank()) {
            System.err.println("error: password not set; use --password / --password-file / "
                    + "--password-stdin / env MOVEBANK_PASSWORD");
            return ExitCode.USAGE_ERROR;
        }

        File baseDir = g.mirrorDir.toFile();
        if (!baseDir.isDirectory()) {
            System.err.println("error: --mirror-dir does not exist or is not a directory: " + baseDir
                    + " (run 'metadata' first to populate it)");
            return ExitCode.IO_ERROR;
        }

        try (ProcessLock ignored = ProcessLock.acquire(baseDir.toPath())) {
            MovebankApiClient client = new MovebankApiClient(
                    g.baseUrl, g.user, password, html -> true);

            EventDataDownloader downloader = (chunkSize != null)
                    ? new EventDataDownloader(client, baseDir, chunkSize)
                    : new EventDataDownloader(client, baseDir);

            EventDataSyncLoop loop = new EventDataSyncLoop(baseDir, downloader);
            if (updateSleep != null) {
                loop.setUpdateSleepMs(updateSleep.toMillis());
            }
            if (!includeStudies.isEmpty()) {
                loop.setStudyIdFilter(includeStudies::contains);
            }

            if (once) {
                loop.runPass();
                return ExitCode.OK;
            }

            // Long-running mode: install a shutdown hook so SIGTERM ends the current pass cleanly.
            Thread runner = Thread.currentThread();
            Thread hook = new Thread(runner::interrupt, "movebank-mirror-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                loop.run();
            } catch (InterruptedException e) {
                logger.info("Interrupted — stopping.");
                Thread.currentThread().interrupt();
                return ExitCode.INTERRUPTED;
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignore) {}
            }
            return ExitCode.OK;
        } catch (ProcessLock.AlreadyHeldException e) {
            System.err.println("error: " + e.getMessage());
            return ExitCode.LOCK_HELD;
        }
    }
}
