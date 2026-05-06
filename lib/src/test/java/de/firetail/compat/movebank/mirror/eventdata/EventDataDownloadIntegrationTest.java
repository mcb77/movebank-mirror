package de.firetail.compat.movebank.mirror.eventdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.firetail.compat.movebank.api.client.LicenseChecker;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.mirror.MirrorTestSupport;
import de.firetail.compat.movebank.mirror.StudyJson;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.File;

/**
 * Downloads one chunk for one study. Requires {@code STUDY_JSON} env var
 * pointing at a previously-mirrored study JSON file.
 */
@Tag("integration")
class EventDataDownloadIntegrationTest {

    @Test
    void downloadsOneStudy() throws Exception {
        MirrorTestSupport.Credentials creds = MirrorTestSupport.requireCredentials();
        String studyJsonPath = System.getenv("STUDY_JSON");
        Assumptions.assumeTrue(studyJsonPath != null && !studyJsonPath.isBlank(),
                "STUDY_JSON not set — skipping");
        File studyJson = new File(studyJsonPath);
        Assumptions.assumeTrue(studyJson.isFile(),
                "STUDY_JSON does not point to a file: " + studyJsonPath);

        File mirrorBaseDir = MirrorTestSupport.freshMirrorDir("event-data-download");

        LicenseChecker autoAccept = html -> true;
        MovebankApiClient client = new MovebankApiClient(
                MirrorTestSupport.MOVEBANK_BASE_URL, creds.user(), creds.password(), autoAccept);

        StudyJson study = new ObjectMapper().readValue(studyJson, StudyJson.class);
        new EventDataDownloader(client, mirrorBaseDir).downloadStudy(study);
    }
}
