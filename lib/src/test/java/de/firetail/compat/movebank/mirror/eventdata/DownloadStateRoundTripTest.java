package de.firetail.compat.movebank.mirror.eventdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DownloadStateRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void freshStateRoundTrip() throws Exception {
        DownloadState before = new DownloadState();

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(before);
        DownloadState after = mapper.readValue(json, DownloadState.class);

        assertNull(after.lastTimestamp);
        assertNull(after.lastUpdateTs);
        assertFalse(after.catchUpComplete);
        assertEquals(EventDataDownloader.MIN_UPDATE_INTERVAL_MS, after.updateIntervalMs);
        assertEquals(0L, after.nextUpdateDue);
    }

    @Test
    void populatedStateRoundTrip() throws Exception {
        DownloadState before = new DownloadState();
        before.lastTimestamp = "2024-01-01 00:00:00.000";
        before.lastUpdateTs = "2024-06-30 12:34:56.789";
        before.catchUpComplete = true;
        before.updateIntervalMs = 60 * 60 * 1_000L;
        before.nextUpdateDue = 1_700_000_000_000L;

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(before);
        DownloadState after = mapper.readValue(json, DownloadState.class);

        assertEquals(before.lastTimestamp, after.lastTimestamp);
        assertEquals(before.lastUpdateTs, after.lastUpdateTs);
        assertEquals(before.catchUpComplete, after.catchUpComplete);
        assertEquals(before.updateIntervalMs, after.updateIntervalMs);
        assertEquals(before.nextUpdateDue, after.nextUpdateDue);
    }
}
