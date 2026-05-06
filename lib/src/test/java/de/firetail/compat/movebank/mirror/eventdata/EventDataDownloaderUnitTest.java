package de.firetail.compat.movebank.mirror.eventdata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDataDownloaderUnitTest {

    @Test
    void rejectsNonPositiveChunkSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new EventDataDownloader(null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new EventDataDownloader(null, null, -1));
    }

    // ── plusOneMs ──────────────────────────────────────────────────────────

    @Test
    void plusOneMsAdvancesByOneMillisecond() {
        assertEquals("2020-11-25 11:42:57.096",
                EventDataDownloader.plusOneMs("2020-11-25 11:42:57.095"));
    }

    @Test
    void plusOneMsRollsOverSecond() {
        assertEquals("2020-11-25 11:42:58.000",
                EventDataDownloader.plusOneMs("2020-11-25 11:42:57.999"));
    }

    @Test
    void plusOneMsPassesThroughUnparseable() {
        assertEquals("not-a-timestamp",
                EventDataDownloader.plusOneMs("not-a-timestamp"));
    }

    // ── applyBackoff ladder ────────────────────────────────────────────────

    @Test
    void applyBackoffMovesFromMinToFirstRung() {
        DownloadState state = new DownloadState();
        state.updateIntervalMs = EventDataDownloader.MIN_UPDATE_INTERVAL_MS; // 5 min
        long before = System.currentTimeMillis();

        EventDataDownloader.applyBackoff(state);

        assertEquals(EventDataDownloader.BACKOFF_LADDER[0], state.updateIntervalMs,
                "first backoff step should land on the first ladder rung (15 min)");
        assertTrue(state.nextUpdateDue >= before + state.updateIntervalMs,
                "nextUpdateDue should be at least now + interval");
    }

    @Test
    void applyBackoffWalksUpTheLadder() {
        DownloadState state = new DownloadState();
        state.updateIntervalMs = EventDataDownloader.MIN_UPDATE_INTERVAL_MS;
        long[] expected = EventDataDownloader.BACKOFF_LADDER;
        for (int i = 0; i < expected.length; i++) {
            EventDataDownloader.applyBackoff(state);
            assertEquals(expected[i], state.updateIntervalMs,
                    "step " + i + " should reach ladder rung " + expected[i]);
        }
    }

    @Test
    void applyBackoffPinsToMaxOnceAtOrAboveTopRung() {
        long max = EventDataDownloader.BACKOFF_LADDER[EventDataDownloader.BACKOFF_LADDER.length - 1];
        DownloadState state = new DownloadState();
        state.updateIntervalMs = max;

        EventDataDownloader.applyBackoff(state);
        assertEquals(max, state.updateIntervalMs);

        EventDataDownloader.applyBackoff(state);
        assertEquals(max, state.updateIntervalMs);
    }
}
