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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Command(name = "eventdata",
        mixinStandardHelpOptions = true,
        description = "Sync event data for studies in --mirror-dir. Default: loop forever; "
                + "use --once for a single pass.")
public class EventDataCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(EventDataCommand.class);

    /** How long a SIGTERM waits for the in-flight download before the JVM exits anyway. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(60);

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

            // SIGTERM/SIGINT: interrupt the runner, then hold the JVM open until the loop
            // has stopped at a (tag, sensor type) boundary. The JVM exits as soon as all
            // hooks return, so the hook must wait — bounded, because a blocking HTTP read
            // does not respond to interrupts. A download cut off by the deadline leaves
            // only a .part file, which the next run discards.
            Thread runner = Thread.currentThread();
            CountDownLatch stopped = new CountDownLatch(1);
            Thread hook = new Thread(() -> {
                logger.info("Shutdown requested — finishing current download (up to {}s)",
                        SHUTDOWN_GRACE.toSeconds());
                runner.interrupt();
                try {
                    stopped.await(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignore) {
                    // JVM is going down regardless
                }
            }, "movebank-mirror-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                if (once) {
                    loop.runPass();
                } else {
                    loop.run();
                }
                if (Thread.currentThread().isInterrupted()) {
                    logger.info("Interrupted — stopped.");
                    return ExitCode.INTERRUPTED;
                }
                return ExitCode.OK;
            } catch (InterruptedException e) {
                logger.info("Interrupted — stopped.");
                Thread.currentThread().interrupt();
                return ExitCode.INTERRUPTED;
            } finally {
                stopped.countDown();
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignore) {}
            }
        } catch (ProcessLock.AlreadyHeldException e) {
            System.err.println("error: " + e.getMessage());
            return ExitCode.LOCK_HELD;
        }
    }
}
