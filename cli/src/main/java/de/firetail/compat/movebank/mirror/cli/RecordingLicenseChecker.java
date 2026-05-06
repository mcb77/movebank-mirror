package de.firetail.compat.movebank.mirror.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.firetail.compat.movebank.api.client.LicenseChecker;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Accepts every license and writes the accepted HTML to
 * {@code <studyId>-license.json} alongside the metadata file. The current study
 * id/name must be set via {@link #setCurrentStudy(String, String)} before each
 * Movebank request that may trigger a license prompt.
 */
final class RecordingLicenseChecker implements LicenseChecker {

    private final File baseDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private String studyId;
    private String studyName;

    RecordingLicenseChecker(File baseDir) {
        this.baseDir = baseDir;
    }

    void setCurrentStudy(String studyId, String studyName) {
        this.studyId = studyId;
        this.studyName = studyName;
    }

    @Override
    public boolean licenseAccepted(String html) {
        try {
            File out = new File(baseDir,
                    String.format("%012d-license.json", Long.parseLong(studyId)));
            mapper.writeValue(out, new AcceptedLicense(studyId, studyName, html));
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final class AcceptedLicense {
        public final String studyId;
        public final String studyName;
        public final String licenseHtml;

        AcceptedLicense(String studyId, String studyName, String licenseHtml) {
            this.studyId = studyId;
            this.studyName = studyName;
            this.licenseHtml = licenseHtml;
        }
    }
}
