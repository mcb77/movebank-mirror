package de.firetail.compat.movebank.mirror.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import de.firetail.compat.movebank.mirror.StudyJson;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Reads a mirror directory written by movebank-mirror: study metadata,
 * deployments, and event rows in the order live Movebank returns them, with
 * each event assigned to its deployment by Movebank's rule.
 * <p>Stateless apart from the base directory; every call reads from disk, so
 * a running sync is picked up immediately. Thread-safe.
 */
public class MirrorReader {

    private final File baseDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public MirrorReader(File baseDir) {
        this.baseDir = baseDir;
    }

    public File baseDir() {
        return baseDir;
    }

    // ── Studies ────────────────────────────────────────────────────────────

    /** The study's metadata, or null if the mirror doesn't have it. */
    public StudyJson study(String studyId) throws IOException {
        File f = MirrorLayout.studyFile(baseDir, studyId);
        return f.exists() ? mapper.readValue(f, StudyJson.class) : null;
    }

    /** Every study in the mirror, in id order. Malformed metadata files are skipped. */
    public List<StudyJson> studies() {
        File[] files = baseDir.listFiles((d, name) -> MirrorLayout.isStudyFileName(name));
        if (files == null) return Collections.emptyList();
        Arrays.sort(files);
        List<StudyJson> studies = new ArrayList<>();
        for (File f : files) {
            try { studies.add(mapper.readValue(f, StudyJson.class)); }
            catch (Exception e) { /* skip malformed file */ }
        }
        return studies;
    }

    /** The study's sensor type ids, sorted as text; empty if the metadata is missing. */
    public List<String> sensorTypeIds(String studyId) throws IOException {
        StudyJson study = study(studyId);
        if (study == null || study.sensorTypeIds == null || study.sensorTypeIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>(study.sensorTypeIds);
        Collections.sort(ids);
        return ids;
    }

    // ── Deployments ────────────────────────────────────────────────────────

    /**
     * The study's deployment windows, optionally only those of the given individuals
     * and/or deployments (empty set: no filter). Deployments without a tag are left out.
     * <p>The window is {@code deploy_on_timestamp} / {@code deploy_off_timestamp}; a blank
     * bound is open. {@code timestamp_start} / {@code timestamp_end} are not used: they are the
     * first and last located fix, and live Movebank assigns the deployment to events outside
     * them too (measured 2026-09-29, study 2911040: this rule matches live's deployment_id on
     * all 114,929 events).
     */
    public List<DeploymentWindow> deploymentWindows(
            String studyId, Set<String> individualIds, Set<String> deploymentIds) throws IOException {
        StudyJson study = study(studyId);
        if (study == null || study.deployments == null) return Collections.emptyList();
        List<DeploymentWindow> windows = new ArrayList<>();
        for (Map<String, String> d : study.deployments) {
            if (individualIds != null && !individualIds.isEmpty() && !individualIds.contains(d.get("individual_id"))) continue;
            if (deploymentIds != null && !deploymentIds.isEmpty() && !deploymentIds.contains(d.get("id")))           continue;
            String tagId = d.get("tag_id");
            if (tagId == null || tagId.isBlank()) continue;
            windows.add(new DeploymentWindow(tagId, d.get("individual_id"), d.get("id"),
                    blankToNull(d.get("deploy_on_timestamp")), blankToNull(d.get("deploy_off_timestamp"))));
        }
        return windows;
    }

    /** All deployment windows of the study, by tag id, in deployment-table order. */
    public Map<String, List<DeploymentWindow>> deploymentsByTag(String studyId) throws IOException {
        Map<String, List<DeploymentWindow>> byTag = new HashMap<>();
        for (DeploymentWindow w : deploymentWindows(studyId, null, null)) {
            byTag.computeIfAbsent(w.tagId(), k -> new ArrayList<>()).add(w);
        }
        return byTag;
    }

    // ── Events ─────────────────────────────────────────────────────────────

    /**
     * The union of the source columns of the given sensor types (empty: all of the study's),
     * in first-seen order: sensor types in numeric id order, and for each the header of the first
     * file of the first tag that has data. One file per sensor type is enough, because
     * all files of a sensor type in a study share their columns.
     */
    public List<String> eventColumns(String studyId, List<String> sensorTypeIds, String tagId) throws Exception {
        File studyDir = MirrorLayout.studyDir(baseDir, studyId);
        LinkedHashSet<String> union = new LinkedHashSet<>();
        if (!studyDir.isDirectory()) return new ArrayList<>(union);
        List<String> ids = new ArrayList<>(resolveSensorTypes(studyId, sensorTypeIds));
        ids.sort(MirrorReader::compareIds);
        for (String stid : ids) {
            for (File tagDir : MirrorLayout.tagDirs(studyDir, tagId)) {
                File[] csvs = MirrorLayout.eventFiles(tagDir, stid);
                if (csvs.length == 0) continue;
                String[] header;
                try (CSVReader reader = new CSVReader(
                        new InputStreamReader(new FileInputStream(csvs[0]), StandardCharsets.UTF_8))) {
                    header = reader.readNext();
                }
                if (header == null) continue; // empty file: learn the columns from the next tag
                Collections.addAll(union, header);
                break;
            }
        }
        return new ArrayList<>(union);
    }

    /**
     * Reads the events selected by {@code query} and hands them to {@code visitor} in live
     * Movebank's order: by sensor type (numeric id); within it by individual (numeric id),
     * and within an individual by timestamp, merged across all tags the individual carried
     * (tags can overlap in time, and a deployment without deploy_on is open towards the past).
     * Events outside every deployment of their tag come last, tag by tag, in file order; they
     * are only read when the query selects no individuals or deployments.
     * <p>An event belongs to the first deployment of its tag whose window contains its
     * timestamp, so an event is never delivered twice.
     * <p>Memory: one pending row per tag of the current individual (k-way merge over the
     * tags' files, each of which is read front to back).
     */
    public void readEvents(EventQuery query, EventVisitor visitor) throws Exception {
        File studyDir = MirrorLayout.studyDir(baseDir, query.studyId());
        if (!studyDir.isDirectory()) return;

        List<String> sensorTypeIds = new ArrayList<>(resolveSensorTypes(query.studyId(), query.sensorTypeIds()));
        if (sensorTypeIds.isEmpty()) return;
        sensorTypeIds.sort(MirrorReader::compareIds);

        Map<String, List<DeploymentWindow>> deploymentsByTag = deploymentsByTag(query.studyId());

        Set<DeploymentWindow> selected = null;
        List<File> dirs;
        if (query.selectsDeployments()) {
            selected = new HashSet<>(deploymentWindows(query.studyId(), query.individualIds(), query.deploymentIds()));
            Set<String> selectedTags = new HashSet<>();
            for (DeploymentWindow w : selected) selectedTags.add(w.tagId());
            dirs = new ArrayList<>();
            for (File d : MirrorLayout.tagDirs(studyDir, query.tagId())) if (selectedTags.contains(d.getName())) dirs.add(d);
        } else {
            dirs = new ArrayList<>(MirrorLayout.tagDirs(studyDir, query.tagId()));
        }
        dirs.sort(Comparator.comparing(File::getName, MirrorReader::compareIds));

        for (String stid : sensorTypeIds) {
            readSensorType(stid, dirs, deploymentsByTag, selected, query, visitor);
        }
    }

    private List<String> resolveSensorTypes(String studyId, Collection<String> requested) throws IOException {
        return requested == null || requested.isEmpty() ? sensorTypeIds(studyId) : new ArrayList<>(requested);
    }

    private void readSensorType(String sensorTypeId, List<File> dirs,
                                Map<String, List<DeploymentWindow>> deploymentsByTag,
                                Set<DeploymentWindow> selected, EventQuery query,
                                EventVisitor visitor) throws Exception {
        // individual -> the tags (in tag order) that carry one of its (selected) deployments
        TreeMap<String, List<File>> tagsByIndividual =
                new TreeMap<>(Comparator.nullsLast(MirrorReader::compareIds));
        for (File dir : dirs) {
            for (DeploymentWindow w : deploymentsByTag.getOrDefault(dir.getName(), List.of())) {
                if (selected != null && !selected.contains(w)) continue;
                List<File> tags = tagsByIndividual.computeIfAbsent(blankToNull(w.individualId()), k -> new ArrayList<>());
                if (!tags.contains(dir)) tags.add(dir);
            }
        }
        for (Map.Entry<String, List<File>> e : tagsByIndividual.entrySet()) {
            String individual = e.getKey();
            List<RowStream> streams = new ArrayList<>();
            for (File dir : e.getValue()) {
                streams.add(new RowStream(streams.size(), dir, sensorTypeId,
                        deploymentsByTag.getOrDefault(dir.getName(), List.of()),
                        w -> w != null && Objects.equals(blankToNull(w.individualId()), individual)
                                && (selected == null || selected.contains(w)),
                        query, visitor));
            }
            merge(streams, visitor);
        }
        if (selected == null) {
            for (File dir : dirs) {
                merge(List.of(new RowStream(0, dir, sensorTypeId,
                        deploymentsByTag.getOrDefault(dir.getName(), List.of()),
                        Objects::isNull, query, visitor)), visitor);
            }
        }
    }

    /** k-way merge by timestamp; ties keep tag order. Closes every stream. */
    private static void merge(List<RowStream> streams, EventVisitor visitor) throws Exception {
        PriorityQueue<RowStream> queue = new PriorityQueue<>(
                Comparator.comparing((RowStream s) -> s.ts, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(s -> s.order));
        try {
            for (RowStream s : streams) if (s.advance()) queue.add(s);
            while (!queue.isEmpty()) {
                RowStream s = queue.poll();
                visitor.event(new Event(s.columns, s.row, s.tagId, s.sensorTypeId, s.ts, s.active));
                if (s.advance()) queue.add(s);
            }
        } finally {
            for (RowStream s : streams) {
                try { s.close(); } catch (IOException ignored) { /* keep closing the others */ }
            }
        }
    }

    /**
     * The rows of one tag folder for one sensor type, read file by file (chronological
     * file names), keeping only rows whose deployment passes {@code accept} and whose
     * timestamp is in the query's range.
     */
    private static final class RowStream implements Closeable {
        final int order;
        final String tagId;
        final String sensorTypeId;
        private final Iterator<File> files;
        private final List<DeploymentWindow> tagWindows;
        private final Predicate<DeploymentWindow> accept;
        private final EventQuery query;
        private final EventVisitor visitor;
        private CSVReader reader;
        private EventColumns fileColumns;
        private int tsIdx;

        // current row
        String[] row;
        String ts;
        DeploymentWindow active;
        EventColumns columns;

        RowStream(int order, File tagDir, String sensorTypeId, List<DeploymentWindow> tagWindows,
                  Predicate<DeploymentWindow> accept, EventQuery query, EventVisitor visitor) {
            this.order = order;
            this.tagId = tagDir.getName();
            this.sensorTypeId = sensorTypeId;
            this.tagWindows = tagWindows;
            this.accept = accept;
            this.query = query;
            this.visitor = visitor;
            this.files = Arrays.asList(MirrorLayout.eventFiles(tagDir, sensorTypeId)).iterator();
        }

        /** Moves to the next accepted row; false when the tag has none left. */
        boolean advance() throws Exception {
            while (true) {
                if (reader == null) {
                    if (!files.hasNext()) { row = null; ts = null; active = null; return false; }
                    open(files.next());
                    continue;
                }
                String[] r = reader.readNext();
                if (r == null) { close(); continue; }
                String t = (tsIdx >= 0 && tsIdx < r.length) ? r[tsIdx] : null;
                DeploymentWindow w = findActive(tagWindows, t);
                if (!accept.test(w)) continue;
                if (!query.inTimeRange(t)) continue;
                row = r; ts = t; active = w; columns = fileColumns;
                return true;
            }
        }

        private void open(File csvFile) throws Exception {
            reader = new CSVReader(new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8));
            String[] header = reader.readNext();
            if (header == null) { close(); return; }
            fileColumns = new EventColumns(header);
            visitor.file(fileColumns);
            tsIdx = fileColumns.indexOf("timestamp");
        }

        @Override
        public void close() throws IOException {
            if (reader != null) { reader.close(); reader = null; }
        }
    }

    /** The deployment window covering {@code ts}, or null if none. */
    private static DeploymentWindow findActive(List<DeploymentWindow> windows, String ts) {
        if (ts == null) return null;
        for (DeploymentWindow w : windows) {
            if (w.contains(ts)) return w;
        }
        return null;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Numeric order for Movebank ids (digit strings); anything else falls back to text order. */
    public static int compareIds(String a, String b) {
        boolean na = a != null && a.matches("\\d+"), nb = b != null && b.matches("\\d+");
        if (na && nb) {
            String x = a.replaceFirst("^0+(?=.)", ""), y = b.replaceFirst("^0+(?=.)", "");
            return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
