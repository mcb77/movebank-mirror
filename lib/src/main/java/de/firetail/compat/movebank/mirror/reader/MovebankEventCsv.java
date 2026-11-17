package de.firetail.compat.movebank.mirror.reader;

import com.opencsv.CSVWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Writes events as live Movebank's {@code entity_type=event} CSV: a header,
 * then one row per event projected to the requested columns. Columns the
 * source file lacks are empty (as Movebank sends missing values); the
 * {@link #SYNTHETIC_COLUMNS} are filled from the file layout and the
 * deployment table when the source has no value for them.
 * <p>Used by movebank-mirror-api for its responses and by anything else that
 * should produce the same bytes. Not thread-safe; one instance per response.
 */
public final class MovebankEventCsv implements EventVisitor {

    /**
     * Columns the reader can fill although the on-disk CSV doesn't carry them.
     * {@code event_id} is synthetic: numbered 1, 2, ... in output order, per response.
     */
    public static final List<String> SYNTHETIC_COLUMNS = List.of(
            "tag_id", "individual_id", "deployment_id", "event_id");

    private final CSVWriter csv;
    private final List<String> columns;
    /** Keyed by header content: every file opened gets its own EventColumns, but a study has few distinct headers. */
    private final Map<List<String>, int[]> projections = new HashMap<>();
    private boolean headerWritten;
    private long nextEventId = 1;

    /**
     * @param csv     where to write; null to use only {@link #project} (then {@link #event} writes nothing)
     * @param columns the output columns, in order (see {@link #defaultColumns})
     */
    public MovebankEventCsv(CSVWriter csv, List<String> columns) {
        this.csv = csv;
        this.columns = List.copyOf(columns);
    }

    /**
     * The columns Movebank sends without an {@code attributes} projection: the union
     * of the source columns ({@link MirrorReader#eventColumns}) plus the synthetic ones.
     */
    public static List<String> defaultColumns(MirrorReader reader, String studyId,
                                              List<String> sensorTypeIds, String tagId) throws Exception {
        LinkedHashSet<String> union = new LinkedHashSet<>(reader.eventColumns(studyId, sensorTypeIds, tagId));
        union.addAll(SYNTHETIC_COLUMNS);
        return new ArrayList<>(union);
    }

    /** The header goes out when the first source file is opened, even if it has no matching rows. */
    @Override
    public void file(EventColumns sourceColumns) {
        if (headerWritten) return;
        if (csv != null) csv.writeNext(columns.toArray(new String[0]), false);
        headerWritten = true;
    }

    @Override
    public void event(Event e) throws IOException {
        String[] row = project(e);
        if (csv == null) return;
        csv.writeNext(row, false);
        // CSVWriter swallows IOExceptions (disk full, client gone); look every 10,000 rows
        // so a failed response stops reading instead of running through the whole study.
        if (nextEventId % 10_000 == 0) checkError();
    }

    /** Throws if the underlying writer failed. Flushes it. Call before reporting success. */
    public void checkError() throws IOException {
        if (csv != null && csv.checkError()) {
            IOException cause = csv.getException();
            throw cause != null ? cause : new IOException("writing the event CSV failed");
        }
    }

    /**
     * The event as an output row, aligned with {@link #columns()}, and numbered: each
     * call takes the next {@code event_id}. For callers that want Movebank's rows
     * without writing CSV.
     */
    public String[] project(Event e) {
        long eventId = nextEventId++;
        int[] projection = projections.computeIfAbsent(e.columns().names(), this::projection);
        String[] row = e.values();
        String[] out = new String[projection.length];
        for (int i = 0; i < projection.length; i++) {
            int src = projection[i];
            // Prefer the source value when it is meaningful; the synthetic value is a
            // fallback for mirrors that didn't download those columns.
            String fromSource = (src >= 0 && src < row.length) ? row[src] : null;
            if (fromSource != null && !fromSource.isEmpty() && !"NA".equals(fromSource)) {
                out[i] = fromSource;
            } else {
                String syn = synthetic(columns.get(i), e, eventId);
                out[i] = syn != null ? syn : "";
            }
        }
        return out;
    }

    /** The output columns. */
    public List<String> columns() {
        return columns;
    }

    /** True once the header went out, i.e. at least one source file was opened. */
    public boolean headerWritten() {
        return headerWritten;
    }

    private int[] projection(List<String> source) {
        int[] p = new int[columns.size()];
        for (int i = 0; i < p.length; i++) p[i] = source.indexOf(columns.get(i)); // first occurrence
        return p;
    }

    private static String synthetic(String column, Event e, long eventId) {
        DeploymentWindow d = e.deployment();
        switch (column) {
            case "tag_id":        return e.tagId();
            case "individual_id": return d != null ? safe(d.individualId()) : "";
            case "deployment_id": return d != null ? safe(d.deploymentId()) : "";
            case "event_id":      return Long.toString(eventId);
            default:              return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
