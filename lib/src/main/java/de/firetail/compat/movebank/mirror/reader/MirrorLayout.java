package de.firetail.compat.movebank.mirror.reader;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Where things live in a mirror directory. The writer (metadata command,
 * {@code EventDataDownloader}) and the readers share these names:
 * <pre>
 * mirror-dir/
 * ├── 000002911040.json              study metadata ({@link de.firetail.compat.movebank.mirror.StudyJson})
 * ├── 000002911040-license.json      accepted-license record
 * └── 000002911040/                  one directory per study
 *     └── &lt;tagId&gt;/                   one directory per tag
 *         ├── state_&lt;sensorTypeId&gt;.json
 *         ├── &lt;sensorTypeId&gt;_&lt;yyyyMMddHHmmss&gt;.csv
 *         └── &lt;sensorTypeId&gt;_update_&lt;yyyyMMddHHmmss&gt;.csv
 * </pre>
 */
public final class MirrorLayout {

    public static final String LICENSE_SUFFIX = "-license.json";

    private MirrorLayout() {
    }

    /**
     * Study ids are zero-padded to 12 digits in file and directory names.
     * @throws IllegalArgumentException if the id is not a non-negative number
     */
    public static String studyKey(String studyId) {
        if (studyId == null || !studyId.matches("\\d{1,18}")) {
            throw new IllegalArgumentException("not a study id: " + studyId);
        }
        return String.format("%012d", Long.parseLong(studyId));
    }

    public static File studyFile(File baseDir, String studyId) {
        return new File(baseDir, studyKey(studyId) + ".json");
    }

    public static File licenseFile(File baseDir, String studyId) {
        return new File(baseDir, studyKey(studyId) + LICENSE_SUFFIX);
    }

    public static File studyDir(File baseDir, String studyId) {
        return new File(baseDir, studyKey(studyId));
    }

    /** True for a study metadata file name (not a license record). */
    public static boolean isStudyFileName(String name) {
        return name.endsWith(".json") && !name.endsWith(LICENSE_SUFFIX);
    }

    /**
     * The tag directories of a study, sorted by name; only {@code tagId}'s when it is
     * given. Sorted because {@code File.listFiles()} returns file-system order.
     */
    public static List<File> tagDirs(File studyDir, String tagId) {
        if (tagId != null) {
            if (!tagId.matches("\\d+")) return Collections.emptyList(); // tag ids are numbers; no paths
            File d = new File(studyDir, tagId);
            return d.isDirectory() ? List.of(d) : Collections.emptyList();
        }
        File[] dirs = studyDir.listFiles(File::isDirectory);
        if (dirs == null) return Collections.emptyList();
        Arrays.sort(dirs);
        return Arrays.asList(dirs);
    }

    /**
     * The event CSV files of one tag and sensor type in name order, which is
     * chronological: catch-up chunks first, then update batches.
     */
    public static File[] eventFiles(File tagDir, String sensorTypeId) {
        File[] files = tagDir.isDirectory() ? tagDir.listFiles(
                (d, name) -> name.startsWith(sensorTypeId + "_") && name.endsWith(".csv")) : null;
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }
}
