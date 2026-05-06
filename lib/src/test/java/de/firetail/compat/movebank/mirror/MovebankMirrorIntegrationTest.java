package de.firetail.compat.movebank.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.firetail.compat.movebank.api.client.LicenseChecker;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Walks every accessible study and writes its metadata as
 * {@code <mirrorBaseDir>/<studyId>.json}. Intended for periodic re-runs;
 * existing files are overwritten.
 */
@Tag("integration")
class MovebankMirrorIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(MovebankMirrorIntegrationTest.class);

    /**
     * Records license HTML the user accepted, one file per study, alongside
     * the metadata JSON. Useful for audit/replay.
     */
    static final class LoggingLicenseChecker implements LicenseChecker {

        private final File base;
        private String studyId;
        private String studyName;

        LoggingLicenseChecker(File base) {
            this.base = base;
        }

        void setCurrentStudy(String studyId, String studyName) {
            this.studyId = studyId;
            this.studyName = studyName;
        }

        public static final class AcceptedLicense {
            public final String studyId;
            public final String studyName;
            public final String licenseHtml;

            AcceptedLicense(String studyId, String studyName, String licenseHtml) {
                this.studyId = studyId;
                this.studyName = studyName;
                this.licenseHtml = licenseHtml;
            }
        }

        @Override
        public boolean licenseAccepted(String html) {
            ObjectMapper mapper = new ObjectMapper();
            AcceptedLicense al = new AcceptedLicense(studyId, studyName, html);
            try {
                mapper.writeValue(
                        new File(base, String.format("%012d-license.json", Long.parseLong(studyId))),
                        al);
                return true;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void mirrorAllAccessibleStudies() throws Exception {
        MirrorTestSupport.Credentials creds = MirrorTestSupport.requireCredentials();
        File base = MirrorTestSupport.freshMirrorDir("mirror");

        LoggingLicenseChecker licenseChecker = new LoggingLicenseChecker(base);
        MovebankApiClient client = new MovebankApiClient(
                MirrorTestSupport.MOVEBANK_BASE_URL, creds.user(), creds.password(), licenseChecker);

        MovebankMirror mirror = new MovebankMirror(client);
        List<StudyId> studies = mirror.getAllStudyIds();
        logger.info("studies: {}", studies.size());

        ObjectMapper mapper = new ObjectMapper();
        for (StudyId studyId : studies) {
            licenseChecker.setCurrentStudy(studyId.studyId(), studyId.studyName());
            try {
                Study study = mirror.getStudyRefData(studyId);
                StudyJson studyJson = new StudyJson(study);
                File studyFile = new File(base,
                        String.format("%012d.json", Long.parseLong(studyId.studyId())));
                mapper.writerWithDefaultPrettyPrinter().writeValue(studyFile, studyJson);
            } catch (Exception e) {
                logger.error("Error for study {}", studyId.studyId(), e);
            }
        }
    }
}
