package de.firetail.compat.movebank.mirror.eventdata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EventRequestBuilderTest {

    @Test
    void convertsCsvTimestampToCompactApiForm() {
        assertEquals("20201125114257095",
                EventRequestBuilder.toApiFormat("2020-11-25 11:42:57.095"));
    }

    @Test
    void passesThroughAlreadyCompactValue() {
        // length != 23 → return as-is
        assertEquals("20201125114257095",
                EventRequestBuilder.toApiFormat("20201125114257095"));
    }

    @Test
    void passesThroughNull() {
        assertNull(EventRequestBuilder.toApiFormat(null));
    }

    @Test
    void passesThroughGarbageOfWrongLength() {
        assertEquals("garbage", EventRequestBuilder.toApiFormat("garbage"));
    }
}
