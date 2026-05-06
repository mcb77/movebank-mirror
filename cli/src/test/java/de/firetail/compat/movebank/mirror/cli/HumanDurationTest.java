package de.firetail.compat.movebank.mirror.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanDurationTest {

    private final HumanDuration parser = new HumanDuration();

    @Test
    void parsesMinutes() {
        assertEquals(Duration.ofMinutes(5), parser.convert("5m"));
    }

    @Test
    void parsesCompoundHumanDuration() {
        assertEquals(Duration.ofHours(1).plusMinutes(30), parser.convert("1h30m"));
    }

    @Test
    void parsesMilliseconds() {
        assertEquals(Duration.ofMillis(250), parser.convert("250ms"));
        // ms must not be confused with the `m` (minutes) group
        assertEquals(Duration.ofMillis(750), parser.convert("750ms"));
    }

    @Test
    void parsesDaysHoursMinutesSecondsMillis() {
        Duration d = parser.convert("1d2h3m4s5ms");
        assertEquals(Duration.ofDays(1).plusHours(2).plusMinutes(3).plusSeconds(4).plusMillis(5), d);
    }

    @Test
    void parsesIso8601() {
        assertEquals(Duration.ofMinutes(5), parser.convert("PT5M"));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(TypeConversionException.class, () -> parser.convert(""));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(TypeConversionException.class, () -> parser.convert("five minutes"));
    }
}
