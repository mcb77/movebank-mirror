package de.firetail.compat.movebank.mirror.eventdata;

import de.firetail.compat.movebank.api.client.LicenseChecker;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.mirror.MirrorTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;

/**
 * Runs a single sync pass over a previously-populated mirror directory.
 * Requires {@code MIRROR_BASE_DIR} env var. Verifies the loop wiring without
 * spinning indefinitely.
 */
@Tag("integration")
class EventDataSyncLoopIntegrationTest {

    @Test
    void singlePass() throws Exception {
        MirrorTestSupport.Credentials creds = MirrorTestSupport.requireCredentials();
        String dir = System.getenv("MIRROR_BASE_DIR");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(),
                "MIRROR_BASE_DIR not set — skipping");
        File mirrorBaseDir = new File(dir);
        Assumptions.assumeTrue(mirrorBaseDir.isDirectory(),
                "MIRROR_BASE_DIR is not a directory: " + dir);

        LicenseChecker autoAccept = html -> true;
        MovebankApiClient client = new MovebankApiClient(
                MirrorTestSupport.MOVEBANK_BASE_URL, creds.user(), creds.password(), autoAccept);

        EventDataSyncLoop loop = new EventDataSyncLoop(client, mirrorBaseDir);
        boolean anyCatchingUp = loop.runPass();
        System.out.println("Still catching up after pass: " + anyCatchingUp);
    }
}
