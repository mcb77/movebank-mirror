package de.firetail.compat.movebank.mirror.eventdata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventDataSyncLoopUnitTest {

    @Test
    void studyIdFromFilenameStripsLeadingZerosAndExtension() {
        assertEquals("2911040", EventDataSyncLoop.studyIdFromFilename("000002911040.json"));
    }

    @Test
    void studyIdFromFilenameKeepsSingleZero() {
        assertEquals("0", EventDataSyncLoop.studyIdFromFilename("0.json"));
    }

    @Test
    void studyIdFromFilenameWithoutLeadingZeros() {
        assertEquals("42", EventDataSyncLoop.studyIdFromFilename("42.json"));
    }
}
