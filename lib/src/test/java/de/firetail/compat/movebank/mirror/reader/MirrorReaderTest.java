package de.firetail.compat.movebank.mirror.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import de.firetail.compat.movebank.mirror.StudyJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorReaderTest {

    private static final String STUDY = "42";
    private static final String GPS = "653";
    private static final String ACC = "2365683";

    @TempDir Path dir;
    private MirrorReader reader;

    @BeforeEach
    void setUp() throws Exception {
        reader = new MirrorReader(dir.toFile());
        // Individual 7 wears tag 10 (Jan-Jun), then tag 9 (May-open, overlapping);
        // individual 8 wears tag 10 from October.
        study(STUDY, Set.of(GPS, ACC), List.of(
                deployment("301", "10", "7", "2020-01-01 00:00:00.000", "2020-06-30 00:00:00.000"),
                deployment("302", "9", "7", "2020-05-01 00:00:00.000", ""),
                deployment("303", "10", "8", "2020-10-01 00:00:00.000", "")));
        events("10", GPS, "20200101000000",
                new String[]{"timestamp", "location_lat"},
                new String[]{"2020-03-01 00:00:00.000", "1"},
                new String[]{"2020-05-20 00:00:00.000", "2"},
                new String[]{"2020-08-01 00:00:00.000", "3"},   // between deployments of tag 10
                new String[]{"2020-11-01 00:00:00.000", "4"});
        events("9", GPS, "20200101000000",
                new String[]{"timestamp", "location_lat"},
                new String[]{"2020-05-10 00:00:00.000", "5"},
                new String[]{"2020-07-01 00:00:00.000", "6"});
        events("9", ACC, "20200101000000",
                new String[]{"timestamp", "acceleration_x"},
                new String[]{"2020-05-11 00:00:00.000", "100"});
    }

    @Test
    void studiesAndMetadata() throws Exception {
        study("5", Set.of(GPS), List.of());
        Files.writeString(MirrorLayout.licenseFile(dir.toFile(), "5").toPath(), "{}");
        assertEquals(List.of("5", "42"), reader.studies().stream().map(s -> s.study.get("id")).toList());
        assertEquals("Study 42", reader.study("42").study.get("name"));
        assertNull(reader.study("999"));
        assertEquals(List.of(ACC, GPS), reader.sensorTypeIds(STUDY), "text order");
    }

    @Test
    void eventsComeInMovebankOrderWithTheirDeployment() throws Exception {
        List<String> got = read(EventQuery.study(STUDY));
        assertEquals(List.of(
                // GPS (653) before acceleration (2365683); individual 7 merged across tags 10 and 9
                "653|10|301|1", "653|9|302|5", "653|10|301|2", "653|9|302|6",
                "653|10|303|4",            // individual 8
                "653|10|-|3",              // undeployed, last
                "2365683|9|302|100"), got);
    }

    @Test
    void individualFilterDropsUndeployedEvents() throws Exception {
        assertEquals(List.of("653|10|303|4"), read(EventQuery.study(STUDY).individuals(Set.of("8"))));
        assertEquals(List.of("653|9|302|5", "653|9|302|6"),
                read(EventQuery.study(STUDY).sensorTypes(List.of(GPS)).deployments(Set.of("302"))));
    }

    @Test
    void tagAndTimeRange() throws Exception {
        assertEquals(List.of("653|9|302|5", "653|9|302|6", "2365683|9|302|100"),
                read(EventQuery.study(STUDY).tag("9")));
        // "to" is compared on its own length: 2020-05-20 includes the whole day
        assertEquals(List.of("653|9|302|5", "653|10|301|2", "2365683|9|302|100"),
                read(EventQuery.study(STUDY).between("2020-05-01", "2020-05-20")));
        assertEquals(List.of("653|10|303|4"),
                read(EventQuery.study(STUDY).between("2020-10-01 00:00:00.000", null)));
    }

    @Test
    void eventColumnsAreTheUnionInSensorTypeOrder() throws Exception {
        assertEquals(List.of("timestamp", "location_lat", "acceleration_x"),
                reader.eventColumns(STUDY, List.of(), null));
        assertEquals(List.of("timestamp", "acceleration_x"),
                reader.eventColumns(STUDY, List.of(ACC), "9"));
    }

    @Test
    void movebankCsvFillsSyntheticColumnsAndNumbersEvents() throws Exception {
        List<String> cols = MovebankEventCsv.defaultColumns(reader, STUDY, List.of(GPS), "9");
        assertEquals(List.of("timestamp", "location_lat", "tag_id", "individual_id", "deployment_id", "event_id"), cols);
        StringWriter sw = new StringWriter();
        try (CSVWriter csv = new CSVWriter(sw, ',', '"', '"', "\n")) {
            reader.readEvents(EventQuery.study(STUDY).sensorTypes(List.of(GPS)).tag("9"),
                    new MovebankEventCsv(csv, List.of("timestamp", "deployment_id", "event_id", "nope")));
        }
        assertEquals("timestamp,deployment_id,event_id,nope\n"
                + "2020-05-10 00:00:00.000,302,1,\n"
                + "2020-07-01 00:00:00.000,302,2,\n", sw.toString());
    }

    @Test
    void missingStudyReadsNothing() throws Exception {
        assertEquals(List.of(), read(EventQuery.study("999")));
        assertEquals(List.of(), reader.eventColumns("999", List.of(), null));
    }

    @Test
    void tagFilterCombinesWithIndividualFilter() throws Exception {
        assertEquals(List.of("653|9|302|5", "653|9|302|6", "2365683|9|302|100"),
                read(EventQuery.study(STUDY).tag("9").individuals(Set.of("7"))));
        assertEquals(List.of(), read(EventQuery.study(STUDY).tag("9").individuals(Set.of("8"))));
    }

    @Test
    void timeRangeMustNotStartAfterItEnds() {
        assertThrows(IllegalArgumentException.class, () -> EventQuery.study(STUDY).between("2021", "2020"));
        // a "to" that is a prefix of "from" is a whole day, not an inverted range
        EventQuery.study(STUDY).between("2020-05-20 10:00", "2020-05-20");
    }

    @Test
    void badIdsAreRejectedOrMatchNothing() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> MirrorLayout.studyKey(null));
        assertThrows(IllegalArgumentException.class, () -> MirrorLayout.studyKey("-5"));
        assertThrows(IllegalArgumentException.class, () -> MirrorLayout.studyKey("../x"));
        assertEquals("000000000042", MirrorLayout.studyKey("42"));
        // tag ids are numbers: no way out of the study directory
        assertEquals(List.of(), read(EventQuery.study(STUDY).tag("../000000000042/9")));
    }

    @Test
    void anEmptyFirstFileDoesNotHideASensorTypesColumns() throws Exception {
        Path empty = MirrorLayout.studyDir(dir.toFile(), STUDY).toPath().resolve("1").resolve(ACC + "_20200101000000.csv");
        Files.createDirectories(empty.getParent());
        Files.writeString(empty, "");
        assertEquals(List.of("timestamp", "acceleration_x"), reader.eventColumns(STUDY, List.of(ACC), null));
    }

    @Test
    void writeErrorsAreReported() throws Exception {
        java.io.Writer broken = new java.io.Writer() {
            @Override public void write(char[] b, int o, int l) throws IOException { throw new IOException("disk full"); }
            @Override public void flush() throws IOException { throw new IOException("disk full"); }
            @Override public void close() { }
        };
        MovebankEventCsv out = new MovebankEventCsv(new CSVWriter(broken), List.of("timestamp"));
        reader.readEvents(EventQuery.study(STUDY), out);
        IOException e = assertThrows(IOException.class, out::checkError);
        assertEquals("disk full", e.getMessage());
    }

    @Test
    void compareIdsIsNumericForDigitStrings() {
        assertTrue(MirrorReader.compareIds("9", "10") < 0);
        assertTrue(MirrorReader.compareIds("010", "9") > 0);
        assertEquals(0, MirrorReader.compareIds("0007", "7"));
        assertEquals(0, MirrorReader.compareIds("000", "0"));
        assertTrue(MirrorReader.compareIds("abc", "abd") < 0);
        assertTrue(MirrorReader.compareIds("10", "9a") < 0, "non-numeric falls back to text order");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** "sensor|tag|deployment|first value after timestamp" per event. */
    private List<String> read(EventQuery q) throws Exception {
        List<String> out = new ArrayList<>();
        reader.readEvents(q, e -> out.add(e.sensorTypeId() + "|" + e.tagId() + "|"
                + (e.deployment() == null ? "-" : e.deployment().deploymentId()) + "|" + e.values()[1]));
        return out;
    }

    private void study(String id, Set<String> sensorTypes, List<Map<String, String>> deployments) throws IOException {
        StudyJson s = new StudyJson();
        s.study = Map.of("id", id, "name", "Study " + id);
        s.deployments = deployments;
        s.tags = Collections.emptyList();
        s.individuals = Collections.emptyList();
        s.sensors = Collections.emptyList();
        s.sensorTypeIds = sensorTypes;
        s.attributesBySensorTypeIDs = Map.of();
        new ObjectMapper().writeValue(MirrorLayout.studyFile(dir.toFile(), id), s);
    }

    private void events(String tag, String sensorType, String fileTs, String[]... rows) throws IOException {
        Path tagDir = MirrorLayout.studyDir(dir.toFile(), STUDY).toPath().resolve(tag);
        Files.createDirectories(tagDir);
        try (CSVWriter w = new CSVWriter(new FileWriter(tagDir.resolve(sensorType + "_" + fileTs + ".csv").toFile()))) {
            for (String[] r : rows) w.writeNext(r);
        }
    }

    private static Map<String, String> deployment(String id, String tag, String individual, String on, String off) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("tag_id", tag);
        d.put("individual_id", individual);
        d.put("deploy_on_timestamp", on);
        d.put("deploy_off_timestamp", off);
        return d;
    }
}
