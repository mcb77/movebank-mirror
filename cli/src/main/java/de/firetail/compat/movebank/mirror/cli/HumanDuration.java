package de.firetail.compat.movebank.mirror.cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human-readable durations like {@code 5m}, {@code 1h30m}, {@code 250ms},
 * falling back to ISO-8601 ({@code PT5M}) so anything {@link Duration#parse} accepts
 * also works.
 */
public final class HumanDuration implements ITypeConverter<Duration> {

    private static final Pattern HUMAN =
            Pattern.compile("(?i)^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:(\\d+)s)?(?:(\\d+)ms)?$");

    @Override
    public Duration convert(String value) {
        if (value == null || value.isBlank()) {
            throw new TypeConversionException("duration is empty");
        }
        String s = value.trim();

        if (s.startsWith("P") || s.startsWith("p")) {
            try {
                return Duration.parse(s);
            } catch (DateTimeParseException e) {
                throw new TypeConversionException("not a valid ISO-8601 duration: " + value);
            }
        }

        Matcher m = HUMAN.matcher(s);
        if (!m.matches() || s.isEmpty() || (m.group(1) == null && m.group(2) == null
                && m.group(3) == null && m.group(4) == null && m.group(5) == null)) {
            throw new TypeConversionException(
                    "expected duration like 5m, 1h30m, 250ms, or ISO-8601 (PT5M); got: " + value);
        }

        long days    = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long hours   = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long minutes = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        long seconds = m.group(4) == null ? 0 : Long.parseLong(m.group(4));
        long millis  = m.group(5) == null ? 0 : Long.parseLong(m.group(5));

        return Duration.ofDays(days)
                .plusHours(hours)
                .plusMinutes(minutes)
                .plusSeconds(seconds)
                .plusMillis(millis);
    }
}
