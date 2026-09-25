package de.firetail.compat.movebank.mirror.eventdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.api.client.RecordCallback;
import de.firetail.compat.movebank.api.client.RequestBuilder;
import de.firetail.compat.movebank.mirror.StudyJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the downloader's on-disk behavior against a scripted fake client. */
class EventDataDownloaderFilesTest {

    private static final String STUDY = "42";
    private static final String TAG = "7";
    private static final String SENSOR = "653";
    private static final List<String> HEADER = List.of("timestamp", "update_ts", "sensor_type_id", "event_id");

    @TempDir File baseDir;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── fake client ────────────────────────────────────────────────────────

    /** One scripted response: rows to stream, and how the stream ends. */
    record Response(List<List<String>> rows, Ending ending) {}

    enum Ending {
        /** Normal end of stream: callback.end() is called. */
        COMPLETE,
        /** What MovebankApiClient.parseResponse does on a mid-stream IOException: return without end(). */
        SILENT_DROP,
        /** Exception propagates out of sendRequest. */
        THROW
    }

    static final class FakeClient extends MovebankApiClient {
        final Deque<Response> responses = new ArrayDeque<>();
        int requests;

        FakeClient() throws Exception {
            super("http://fake.invalid", "u", "p", html -> true);
        }

        @Override
        public void sendRequest(RequestBuilder request, RecordCallback callback) throws Exception {
            requests++;
            Response r = responses.removeFirst();
            callback.start(HEADER);
            for (List<String> row : r.rows) {
                callback.record(row, 0, 0);
            }
            switch (r.ending) {
                case COMPLETE -> callback.end();
                case SILENT_DROP -> { }
                case THROW -> throw new java.io.IOException("connection reset");
            }
        }
    }

    private static List<String> row(int i) {
        String ts = String.format("2020-01-01 00:00:%02d.000", i);
        return List.of(ts, "2020-02-01 00:00:00.000", SENSOR, "e" + i);
    }

    private static List<List<String>> rows(int from, int toExclusive) {
        List<List<String>> out = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) out.add(row(i));
        return out;
    }

    private static StudyJson study() {
        StudyJson s = new StudyJson();
        s.study = Map.of("id", STUDY);
        s.sensors = List.of(Map.of("tag_id", TAG, "sensor_type_id", SENSOR));
        s.sensorTypeIds = Set.of(SENSOR);
        s.attributesBySensorTypeIDs = Map.of(SENSOR, List.of());
        return s;
    }

    private File tagDir() {
        return new File(baseDir, String.format("%012d", Long.parseLong(STUDY)) + "/" + TAG);
    }

    private String[] tagDirFiles() {
        String[] names = tagDir().list();
        if (names == null) return new String[0];
        Arrays.sort(names);
        return names;
    }

    private String[] csvFiles() {
        return Arrays.stream(tagDirFiles()).filter(n -> n.endsWith(".csv")).toArray(String[]::new);
    }

    private DownloadState state() throws Exception {
        return mapper.readValue(new File(tagDir(), "state_" + SENSOR + ".json"), DownloadState.class);
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    void silentlyDroppedStreamDoesNotCompleteCatchUp() throws Exception {
        FakeClient client = new FakeClient();
        client.responses.add(new Response(rows(0, 3), Ending.SILENT_DROP));
        EventDataDownloader downloader = new EventDataDownloader(client, baseDir, 10);

        downloader.downloadStudy(study());

        assertEquals(0, csvFiles().length, "partial download must not be kept");
        assertFalse(Arrays.stream(tagDirFiles()).anyMatch(n -> n.endsWith(".part")), "no .part leftovers");
        assertFalse(new File(tagDir(), "state_" + SENSOR + ".json").exists(),
                "state must not advance, let alone mark catch-up complete");
    }

    @Test
    void exceptionMidStreamLeavesNoFilesAndRetriesFromSameCursor() throws Exception {
        FakeClient client = new FakeClient();
        client.responses.add(new Response(rows(0, 3), Ending.THROW));
        client.responses.add(new Response(rows(0, 3), Ending.COMPLETE));
        EventDataDownloader downloader = new EventDataDownloader(client, baseDir, 10);

        downloader.downloadStudy(study());
        assertEquals(0, csvFiles().length);

        downloader.downloadStudy(study());
        assertEquals(1, csvFiles().length);
        assertEquals(4, Files.readAllLines(new File(tagDir(), csvFiles()[0]).toPath()).size(),
                "header + 3 rows, no duplicates from the failed attempt");
        assertTrue(state().catchUpComplete);
    }

    @Test
    void backToBackChunksWithinOneSecondGetDistinctFiles() throws Exception {
        FakeClient client = new FakeClient();
        client.responses.add(new Response(rows(0, 2), Ending.COMPLETE)); // full chunk
        client.responses.add(new Response(rows(1, 3), Ending.COMPLETE)); // full chunk
        client.responses.add(new Response(rows(2, 3), Ending.COMPLETE)); // short → complete
        EventDataDownloader downloader = new EventDataDownloader(client, baseDir, 2);

        assertTrue(downloader.downloadStudy(study()));
        assertTrue(downloader.downloadStudy(study()));
        assertFalse(downloader.downloadStudy(study()));

        String[] csvs = csvFiles();
        assertEquals(3, csvs.length, "no chunk may overwrite another: " + Arrays.toString(csvs));
        int dataRows = 0;
        for (String f : csvs) dataRows += Files.readAllLines(new File(tagDir(), f).toPath()).size() - 1;
        assertEquals(5, dataRows, "2 + 2 + 1 rows (boundary rows duplicated by the inclusive cursor)");
    }

    @Test
    void completedCatchUpUsesStartFloorAsUpdateCursor() throws Exception {
        FakeClient client = new FakeClient();
        client.responses.add(new Response(rows(0, 2), Ending.COMPLETE));
        client.responses.add(new Response(rows(1, 2), Ending.COMPLETE));
        EventDataDownloader downloader = new EventDataDownloader(client, baseDir, 2);

        String before = EventDataDownloader.catchUpUpdateTsFloor(Instant.now());
        downloader.downloadStudy(study());
        DownloadState mid = state();
        assertNotNull(mid.catchUpStartUpdateTs);
        assertTrue(mid.catchUpStartUpdateTs.compareTo(before) >= 0);

        downloader.downloadStudy(study());
        DownloadState done = state();
        assertTrue(done.catchUpComplete);
        assertEquals(mid.catchUpStartUpdateTs, done.lastUpdateTs,
                "update cursor must be the catch-up start floor, not the max update_ts seen");
        assertEquals(null, done.catchUpStartUpdateTs);
    }

    @Test
    void pairWithNoDataStillGetsAnUpdateCursor() throws Exception {
        FakeClient client = new FakeClient();
        client.responses.add(new Response(List.of(), Ending.COMPLETE));
        client.responses.add(new Response(List.of(row(5)), Ending.COMPLETE));
        EventDataDownloader downloader = new EventDataDownloader(client, baseDir, 10);

        downloader.downloadStudy(study());
        assertTrue(state().catchUpComplete);
        assertNotNull(state().lastUpdateTs, "without a cursor the pair would never poll for new data");
        assertEquals(0, csvFiles().length);

        downloader.downloadStudy(study()); // first update pass is due immediately
        assertEquals(2, client.requests);
        assertEquals(1, csvFiles().length);
        assertTrue(csvFiles()[0].startsWith(SENSOR + "_update_"));
    }

    @Test
    void corruptStateSkipsPairInsteadOfRestartingFromScratch() throws Exception {
        FakeClient client = new FakeClient();
        EventDataDownloader downloader = new EventDataDownloader(client, baseDir, 10);
        tagDir().mkdirs();
        Files.writeString(new File(tagDir(), "state_" + SENSOR + ".json").toPath(), "{ \"catchUp");

        downloader.downloadStudy(study());

        assertEquals(0, client.requests, "must not re-download the pair's history");
    }

    @Test
    void stalePartFilesFromAKilledRunAreRemoved() throws Exception {
        FakeClient client = new FakeClient();
        client.responses.add(new Response(rows(0, 1), Ending.COMPLETE));
        EventDataDownloader downloader = new EventDataDownloader(client, baseDir, 10);
        tagDir().mkdirs();
        Files.writeString(new File(tagDir(), SENSOR + "_20200101000000.csv.part").toPath(), "half");

        downloader.downloadStudy(study());

        assertArrayEquals(new String[0],
                Arrays.stream(tagDirFiles()).filter(n -> n.endsWith(".part")).toArray());
        assertEquals(1, csvFiles().length);
    }

    @Test
    void interruptedThreadMakesNoRequests() throws Exception {
        FakeClient client = new FakeClient();
        EventDataDownloader downloader = new EventDataDownloader(client, baseDir, 10);

        Thread.currentThread().interrupt();
        try {
            downloader.downloadStudy(study());
        } finally {
            assertTrue(Thread.interrupted(), "interrupt flag must be preserved (and is cleared here)");
        }
        assertEquals(0, client.requests);
    }
}
