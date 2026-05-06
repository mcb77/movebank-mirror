package de.firetail.compat.movebank.mirror.eventdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.mirror.StudyJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Continuously syncs event data for all studies found in the mirror base directory.
 *
 * <p>Each pass processes one catch-up chunk (or one update batch) per (tag, sensorType)
 * pair across all studies. If any study is still catching up, the next pass starts
 * immediately; once all are in update mode the loop sleeps before polling again.
 *
 * <p>Study JSON files are discovered by scanning {@code mirrorBaseDir} for {@code *.json}
 * files. New studies added between passes are picked up automatically.
 */
public class EventDataSyncLoop {

    private static final Logger logger = LoggerFactory.getLogger(EventDataSyncLoop.class);

    /** Default pause between update passes once all studies are caught up. */
    public static final long DEFAULT_UPDATE_SLEEP_MS = 5 * 60 * 1_000L;

    private final File mirrorBaseDir;
    private final EventDataDownloader downloader;
    private final ObjectMapper mapper = new ObjectMapper();

    private long updateSleepMs = DEFAULT_UPDATE_SLEEP_MS;
    private Predicate<String> studyIdFilter = id -> true;

    public EventDataSyncLoop(MovebankApiClient client, File mirrorBaseDir) {
        this(mirrorBaseDir, new EventDataDownloader(client, mirrorBaseDir));
    }

    /**
     * Construct with a pre-configured downloader (e.g. with a custom chunk size).
     * The downloader's {@code mirrorBaseDir} should match the one passed here.
     */
    public EventDataSyncLoop(File mirrorBaseDir, EventDataDownloader downloader) {
        this.mirrorBaseDir = mirrorBaseDir;
        this.downloader = downloader;
    }

    /** Sets how long the loop sleeps between passes once all studies are caught up. */
    public EventDataSyncLoop setUpdateSleepMs(long updateSleepMs) {
        if (updateSleepMs < 0) {
            throw new IllegalArgumentException("updateSleepMs must be non-negative, got " + updateSleepMs);
        }
        this.updateSleepMs = updateSleepMs;
        return this;
    }

    /**
     * Restricts the loop to study JSON files whose study id (the basename minus
     * the {@code .json} suffix, leading zeros stripped) is accepted by the predicate.
     * Default accepts every study.
     */
    public EventDataSyncLoop setStudyIdFilter(Predicate<String> studyIdFilter) {
        this.studyIdFilter = studyIdFilter == null ? id -> true : studyIdFilter;
        return this;
    }

    // ── Public entry point ─────────────────────────────────────────────────

    /**
     * Runs indefinitely. Blocks the calling thread.
     * Interrupt the thread to stop gracefully.
     */
    public void run() throws InterruptedException {
        logger.info("Event data sync loop starting, mirrorBaseDir={}", mirrorBaseDir.getAbsolutePath());
        while (!Thread.currentThread().isInterrupted()) {
            boolean anyCatchingUp = runPass();
            if (anyCatchingUp) {
                logger.debug("Catch-up in progress — looping immediately");
            } else {
                logger.info("All studies up to date — sleeping {}s before next update pass",
                        updateSleepMs / 1_000);
                Thread.sleep(updateSleepMs);
            }
        }
        logger.info("Event data sync loop stopped.");
    }

    // ── Single pass ────────────────────────────────────────────────────────

    /**
     * Processes all study JSON files found in the mirror directory.
     *
     * @return true if at least one study still has catch-up work remaining
     */
    public boolean runPass() {
        File[] jsonFiles = mirrorBaseDir.listFiles((dir, name) ->
                name.endsWith(".json") && !name.endsWith("-license.json"));
        if (jsonFiles == null || jsonFiles.length == 0) {
            logger.warn("No study JSON files found in {}", mirrorBaseDir.getAbsolutePath());
            return false;
        }
        Arrays.sort(jsonFiles); // deterministic order

        int considered = 0;
        int catchingUp = 0;
        int errors = 0;

        for (File jsonFile : jsonFiles) {
            String studyId = studyIdFromFilename(jsonFile.getName());
            if (!studyIdFilter.test(studyId)) {
                continue;
            }
            considered++;
            try {
                StudyJson study = mapper.readValue(jsonFile, StudyJson.class);
                boolean stillCatchingUp = downloader.downloadStudy(study);
                if (stillCatchingUp) catchingUp++;
            } catch (Exception e) {
                errors++;
                logger.error("Failed to process study JSON {}: {}", jsonFile.getName(), e.getMessage(), e);
            }
        }

        logger.info("Pass complete — {}/{} studies still catching up, {} study-level errors",
                catchingUp, considered, errors);
        return catchingUp > 0;
    }

    /** Strips {@code .json} and leading zeros from a mirror file name. */
    static String studyIdFromFilename(String fileName) {
        String base = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
        int i = 0;
        while (i < base.length() - 1 && base.charAt(i) == '0') i++;
        return base.substring(i);
    }
}
