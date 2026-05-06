package de.firetail.compat.movebank.mirror.eventdata;

import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.api.client.Record;
import de.firetail.compat.movebank.api.client.RequestBuilderEvent;
import de.firetail.compat.movebank.mirror.MirrorTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Probes whether the Movebank API honours the limit parameter for event
 * queries. Study 2911040, tag 2911107, sensor 653 (GPS) — known to have data.
 */
@Tag("integration")
class EventApiProbeIntegrationTest {

    private static final String STUDY_ID = "2911040";
    private static final String TAG_ID = "2911107";
    private static final String SENSOR_TYPE_ID = "653";

    @Test
    void limitParameter() throws Exception {
        MirrorTestSupport.Credentials creds = MirrorTestSupport.requireCredentials();
        MovebankApiClient client = new MovebankApiClient(
                MirrorTestSupport.MOVEBANK_BASE_URL, creds.user(), creds.password(), html -> true);

        RequestBuilderEvent request = new RequestBuilderEvent(STUDY_ID, SENSOR_TYPE_ID,
                List.of("timestamp", "location_lat", "location_long"));
        request.setTagId(TAG_ID);
        request.setLimit(1);

        List<Record> records = client.readAll(request);

        System.out.println("Rows returned with limit=1: " + records.size());
        if (!records.isEmpty()) {
            System.out.println("First row timestamp: " + records.get(0).getStringValue("timestamp"));
        }
    }
}
