package de.firetail.compat.movebank.mirror.eventdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.api.client.RecordCallback;
import de.firetail.compat.movebank.mirror.StudyJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Downloads event data for one study into a per-tag CSV folder structure,
 * with chunked catch-up and incremental update modes plus an exponential
 * backoff ladder.
 */
public class EventDataDownloader {

    private static final Logger logger = LoggerFactory.getLogger(EventDataDownloader.class);

    /**
     * Maximum records per catch-up chunk. Once this limit is hit the stream is
     * aborted and the last seen timestamp is saved as the cursor for the next run.
     * Keeps individual requests reasonably sized and ensures fairness across tags/studies.
     */
    public static final int CHUNK_SIZE = 50_000;

    // ── Update backoff ladder ───────────────────────────────────────────────
    // After each empty update pass the interval steps up to the next level.
    // After any pass with new rows it resets to MIN.

    public static final long MIN_UPDATE_INTERVAL_MS = 5 * 60 * 1_000L;     //  5 min
    static final long[] BACKOFF_LADDER = {
                15 * 60 * 1_000L,        //  15 min
                 1 * 60 * 60 * 1_000L,   //   1 h
                 6 * 60 * 60 * 1_000L,   //   6 h
                24 * 60 * 60 * 1_000L,   //  24 h
             7 * 24 * 60 * 60 * 1_000L,  //   7 d  (max)
    };

    private static final DateTimeFormatter FILE_TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private static final String UPDATE_TS = "update_ts";
    private static final String TIMESTAMP = "timestamp";
    private static final String SENSOR_TYPE_ID = "sensor_type_id";

    private final MovebankApiClient client;
    private final File mirrorBaseDir;
    private final int chunkSize;
    private final ObjectMapper mapper = new ObjectMapper();

    public EventDataDownloader(MovebankApiClient client, File mirrorBaseDir) {
        this(client, mirrorBaseDir, CHUNK_SIZE);
    }

    public EventDataDownloader(MovebankApiClient client, File mirrorBaseDir, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive, got " + chunkSize);
        }
        this.client = client;
        this.mirrorBaseDir = mirrorBaseDir;
        this.chunkSize = chunkSize;
    }

    // ── Public entry point ─────────────────────────────────────────────────

    /**
     * Downloads one chunk (catch-up) or one update batch per (tag, sensorType) pair.
     *
     * @return true if at least one (tag, sensorType) pair is still in catch-up mode
     *         and successfully downloaded a full chunk — caller should loop immediately.
     *         Returns false when all pairs are in update mode or when errors occurred
     *         (errors are logged but do not force an immediate retry loop).
     */
    public boolean downloadStudy(StudyJson study) throws Exception {
        String studyId = study.study.get("id");
        File studyDir = new File(mirrorBaseDir, String.format("%012d", Long.parseLong(studyId)));
        studyDir.mkdirs();

        Map<String, Set<String>> sensorTypesByTag = new LinkedHashMap<>();
        for (Map<String, String> sensor : study.sensors) {
            String tagId = sensor.get("tag_id");
            String sensorTypeId = sensor.get("sensor_type_id");
            sensorTypesByTag.computeIfAbsent(tagId, k -> new LinkedHashSet<>()).add(sensorTypeId);
        }

        logger.info("Study {} — {} tags", studyId, sensorTypesByTag.size());

        boolean anyCatchingUp = false;
        for (Map.Entry<String, Set<String>> entry : sensorTypesByTag.entrySet()) {
            String tagId = entry.getKey();
            File tagDir = new File(studyDir, tagId);
            tagDir.mkdirs();

            for (String sensorTypeId : entry.getValue()) {
                List<String> attributes = study.attributesBySensorTypeIDs.get(sensorTypeId);
                if (attributes == null) attributes = Collections.emptyList();

                List<String> attrs = new ArrayList<>(attributes);
                if (!attrs.contains(UPDATE_TS))      attrs.add(UPDATE_TS);
                if (!attrs.contains(TIMESTAMP))      attrs.add(TIMESTAMP);
                if (!attrs.contains(SENSOR_TYPE_ID)) attrs.add(SENSOR_TYPE_ID);

                try {
                    boolean stillCatchingUp = downloadTagSensor(studyId, tagId, sensorTypeId, attrs, tagDir);
                    if (stillCatchingUp) anyCatchingUp = true;
                } catch (Exception e) {
                    // Error logged — do not count as "still catching up" to avoid spinning the loop
                    logger.error("Failed: studyId={} tagId={} sensorTypeId={}", studyId, tagId, sensorTypeId, e);
                }
            }
        }
        return anyCatchingUp;
    }

    // ── Mode dispatch ──────────────────────────────────────────────────────

    /** Returns true if catch-up is still in progress (more chunks remain). */
    private boolean downloadTagSensor(String studyId, String tagId, String sensorTypeId,
                                      List<String> attributes, File tagDir) throws Exception {
        File stateFile = new File(tagDir, "state_" + sensorTypeId + ".json");
        DownloadState state = readState(stateFile);

        if (!state.catchUpComplete) {
            return runCatchUp(studyId, tagId, sensorTypeId, attributes, tagDir, stateFile, state);
        }

        long now = System.currentTimeMillis();
        if (now < state.nextUpdateDue) {
            logger.debug("Update skipped (next due in {}s): tagId={} sensorTypeId={}",
                    (state.nextUpdateDue - now) / 1_000, tagId, sensorTypeId);
            return false;
        }

        runUpdate(studyId, tagId, sensorTypeId, attributes, tagDir, stateFile, state);
        return false;
    }

    // ── Catch-up mode ──────────────────────────────────────────────────────

    /**
     * Downloads one chunk of up to {@code chunkSize} records starting from the last known timestamp.
     * Returns true if the chunk limit was hit and more catch-up chunks remain.
     */
    private boolean runCatchUp(String studyId, String tagId, String sensorTypeId,
                                List<String> attributes, File tagDir,
                                File stateFile, DownloadState state) throws Exception {

        String fileTs = FILE_TS_FMT.format(Instant.now());
        File csvFile = new File(tagDir, sensorTypeId + "_" + fileTs + ".csv");

        if (state.lastTimestamp == null) {
            logger.info("Catch-up start: tagId={} sensorTypeId={}", tagId, sensorTypeId);
        } else {
            logger.info("Catch-up resume from {}: tagId={} sensorTypeId={}", state.lastTimestamp, tagId, sensorTypeId);
        }

        EventRequestBuilder request = buildRequest(studyId, sensorTypeId, attributes, tagId);
        if (state.lastTimestamp != null) {
            request.setTimestampStart(state.lastTimestamp);
        }

        DownloadResult result = downloadChunk(request, csvFile, chunkSize, sensorTypeId);
        updateMaxUpdateTs(state, result.maxUpdateTs);

        if (result.rowCount < chunkSize) {
            state.catchUpComplete = true;
            state.lastTimestamp = null;
            logger.info("Catch-up complete ({} rows): tagId={} sensorTypeId={}",
                    result.rowCount, tagId, sensorTypeId);
            saveState(stateFile, state);
            return false;
        } else {
            // Hit the limit — determine best cursor for next chunk
            if (result.lastTimestamp != null) {
                state.lastTimestamp = result.lastTimestamp;
                logger.info("Chunk limit reached, cursor={}: tagId={} sensorTypeId={}",
                        result.lastTimestamp, tagId, sensorTypeId);
            } else {
                // Entire chunk was wrong sensor type — advance using any-row timestamp to escape the dead zone
                state.lastTimestamp = result.lastAnyTimestamp;
                logger.warn("Chunk had 0 matching rows (sensorTypeId={}), advancing cursor via wrong-type rows to {}: tagId={}",
                        sensorTypeId, result.lastAnyTimestamp, tagId);
            }
            saveState(stateFile, state);
            return true;
        }
    }

    // ── Update mode ────────────────────────────────────────────────────────

    private void runUpdate(String studyId, String tagId, String sensorTypeId,
                            List<String> attributes, File tagDir,
                            File stateFile, DownloadState state) throws Exception {

        if (state.lastUpdateTs == null) {
            logger.warn("Update mode but no lastUpdateTs — skipping tagId={} sensorTypeId={}", tagId, sensorTypeId);
            return;
        }

        String fileTs = FILE_TS_FMT.format(Instant.now());
        File csvFile = new File(tagDir, sensorTypeId + "_update_" + fileTs + ".csv");
        logger.info("Update since {}: tagId={} sensorTypeId={}", state.lastUpdateTs, tagId, sensorTypeId);

        EventRequestBuilder request = buildRequest(studyId, sensorTypeId, attributes, tagId);
        request.setUpdateTsStart(plusOneMs(state.lastUpdateTs));

        // Updates are typically small — no chunk limit applied
        DownloadResult result = downloadChunk(request, csvFile, Integer.MAX_VALUE, sensorTypeId);

        if (result.rowCount == 0) {
            applyBackoff(state);
            logger.info("No updates (next in {}s): tagId={} sensorTypeId={}",
                    state.updateIntervalMs / 1_000, tagId, sensorTypeId);
        } else {
            resetBackoff(state);
            updateMaxUpdateTs(state, result.maxUpdateTs);
            logger.info("{} updated rows (interval reset to {}s): tagId={} sensorTypeId={}",
                    result.rowCount, state.updateIntervalMs / 1_000, tagId, sensorTypeId);
        }
        saveState(stateFile, state);
    }

    // ── Core download ──────────────────────────────────────────────────────

    /**
     * Streams records into a CSV file, stopping after maxRows records.
     * Rows whose sensor_type_id does not match expectedSensorTypeId are skipped
     * (not written to CSV, not tracked) but still counted toward maxRows —
     * workaround for a Movebank API bug that mixes sensor types in one response.
     * Deletes the file if no matching data rows were written.
     */
    private DownloadResult downloadChunk(EventRequestBuilder request, File csvFile,
                                          int maxRows, String expectedSensorTypeId) throws Exception {
        UpdateTsTracker tracker = new UpdateTsTracker();
        int[] rowCount = {0}; // total rows received (including wrong sensor type)
        int[] written  = {0}; // rows actually written to CSV

        try (OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(csvFile), StandardCharsets.UTF_8);
             CSVWriter csvWriter = new CSVWriter(osw)) {

            client.sendRequest(request, new RecordCallback() {
                @Override
                public void start(List<String> header) throws Exception {
                    csvWriter.writeNext(header.toArray(new String[0]));
                    tracker.setHeader(header);
                }
                @Override
                public void record(List<String> values, long lineStart, long lineEnd) throws Exception {
                    rowCount[0]++;
                    tracker.observeAny(values);
                    if (tracker.matchesSensorType(values, expectedSensorTypeId)) {
                        csvWriter.writeNext(values.toArray(new String[0]));
                        tracker.observe(values);
                        written[0]++;
                    }
                    if (rowCount[0] >= maxRows) {
                        csvWriter.flush();
                        throw new StopStreamingException();
                    }
                }
                @Override
                public void end() throws Exception {
                    csvWriter.flush();
                }
            });

        } catch (StopStreamingException e) {
            // expected — chunk limit reached, file already flushed
            // brief pause so the server-side connection closes before we fire the next request
            Thread.sleep(500);
        }

        if (written[0] == 0) {
            csvFile.delete();
        }

        if (rowCount[0] != written[0]) {
            logger.warn("Filtered {} wrong-sensor-type rows (expected sensorTypeId={}): {}",
                    rowCount[0] - written[0], expectedSensorTypeId, csvFile.getName());
        }

        return new DownloadResult(rowCount[0], tracker.getMaxUpdateTs(),
                tracker.getLastTimestamp(), tracker.getLastAnyTimestamp());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private EventRequestBuilder buildRequest(String studyId, String sensorTypeId,
                                              List<String> attributes, String tagId) {
        EventRequestBuilder request = new EventRequestBuilder(studyId, sensorTypeId, attributes);
        request.setTagId(tagId);
        return request;
    }

    /**
     * Steps {@code state.updateIntervalMs} up to the next ladder rung. Once the
     * interval has reached or passed the highest rung, it stays there.
     */
    static void applyBackoff(DownloadState state) {
        long next = BACKOFF_LADDER[BACKOFF_LADDER.length - 1]; // pinned to max if already at/above all rungs
        for (long level : BACKOFF_LADDER) {
            if (state.updateIntervalMs < level) {
                next = level;
                break;
            }
        }
        state.updateIntervalMs = next;
        state.nextUpdateDue = System.currentTimeMillis() + next;
    }

    private void resetBackoff(DownloadState state) {
        state.updateIntervalMs = MIN_UPDATE_INTERVAL_MS;
        state.nextUpdateDue = System.currentTimeMillis() + MIN_UPDATE_INTERVAL_MS;
    }

    private static final DateTimeFormatter CSV_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Adds 1 ms to a CSV-format timestamp so update queries are strictly exclusive. */
    static String plusOneMs(String csvTs) {
        try {
            return LocalDateTime.parse(csvTs, CSV_TS_FMT)
                    .plusNanos(1_000_000)
                    .format(CSV_TS_FMT);
        } catch (Exception e) {
            return csvTs; // pass through; API will report any format error
        }
    }

    private void updateMaxUpdateTs(DownloadState state, String candidate) {
        if (candidate == null) return;
        if (state.lastUpdateTs == null || candidate.compareTo(state.lastUpdateTs) > 0) {
            state.lastUpdateTs = candidate;
        }
    }

    private DownloadState readState(File stateFile) {
        if (!stateFile.exists()) return new DownloadState();
        try {
            return mapper.readValue(stateFile, DownloadState.class);
        } catch (Exception e) {
            logger.warn("Could not read state {}: {}", stateFile.getName(), e.getMessage());
            return new DownloadState();
        }
    }

    private void saveState(File stateFile, DownloadState state) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(stateFile, state);
        } catch (Exception e) {
            logger.error("Could not save state {}", stateFile.getName(), e);
        }
    }

    // ── Inner types ────────────────────────────────────────────────────────

    /** Thrown from RecordCallback to abort the stream early (chunk limit). */
    private static class StopStreamingException extends RuntimeException {
        StopStreamingException() {
            super(null, null, true, false); // suppress stacktrace — this is flow control
        }
    }

    private static class DownloadResult {
        final int rowCount;
        final String maxUpdateTs;
        final String lastTimestamp;    // last timestamp from a matching row
        final String lastAnyTimestamp; // last timestamp from any row (fallback cursor)

        DownloadResult(int rowCount, String maxUpdateTs, String lastTimestamp, String lastAnyTimestamp) {
            this.rowCount = rowCount;
            this.maxUpdateTs = maxUpdateTs;
            this.lastTimestamp = lastTimestamp;
            this.lastAnyTimestamp = lastAnyTimestamp;
        }
    }

    private static class UpdateTsTracker {
        private int updateTsIndex       = -1;
        private int timestampIndex      = -1;
        private int sensorTypeIndex     = -1;
        private String maxUpdateTs      = null;
        private String lastTimestamp    = null; // matching rows only
        private String lastAnyTimestamp = null; // all rows — fallback cursor

        void setHeader(List<String> header) {
            for (int i = 0; i < header.size(); i++) {
                if (UPDATE_TS.equals(header.get(i)))      updateTsIndex   = i;
                if (TIMESTAMP.equals(header.get(i)))      timestampIndex  = i;
                if (SENSOR_TYPE_ID.equals(header.get(i))) sensorTypeIndex = i;
            }
        }

        boolean matchesSensorType(List<String> values, String expected) {
            if (sensorTypeIndex < 0 || sensorTypeIndex >= values.size()) return true;
            return expected.equals(values.get(sensorTypeIndex));
        }

        /** Called for every row to track stream position regardless of sensor type. */
        void observeAny(List<String> values) {
            if (timestampIndex >= 0 && timestampIndex < values.size()) {
                String ts = values.get(timestampIndex);
                if (ts != null && !ts.isEmpty()) lastAnyTimestamp = ts;
            }
        }

        /** Called only for rows that passed the sensor type filter. */
        void observe(List<String> values) {
            if (updateTsIndex >= 0 && updateTsIndex < values.size()) {
                String ts = values.get(updateTsIndex);
                if (ts != null && !ts.isEmpty()) {
                    if (maxUpdateTs == null || ts.compareTo(maxUpdateTs) > 0) {
                        maxUpdateTs = ts;
                    }
                }
            }
            if (timestampIndex >= 0 && timestampIndex < values.size()) {
                String ts = values.get(timestampIndex);
                if (ts != null && !ts.isEmpty()) lastTimestamp = ts;
            }
        }

        String getMaxUpdateTs()      { return maxUpdateTs; }
        String getLastTimestamp()    { return lastTimestamp; }
        String getLastAnyTimestamp() { return lastAnyTimestamp; }
    }
}
