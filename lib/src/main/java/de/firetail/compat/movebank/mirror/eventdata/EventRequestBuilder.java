package de.firetail.compat.movebank.mirror.eventdata;

import de.firetail.compat.movebank.api.client.RequestBuilderEvent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds Movebank event requests, translating CSV-format timestamps
 * ({@code yyyy-MM-dd HH:mm:ss.SSS}) into the compact form expected by the
 * Movebank API ({@code yyyyMMddHHmmssSSS}).
 */
class EventRequestBuilder extends RequestBuilderEvent {

    /** Format of timestamp values as they appear in Movebank CSV data. */
    private static final DateTimeFormatter CSV_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Format expected by Movebank API query parameters. */
    private static final DateTimeFormatter API_TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    EventRequestBuilder(String studyId, String sensorTypeId, List<String> attributes) {
        super(studyId, sensorTypeId, attributes);
    }

    void setUpdateTsStart(String updateTs) {
        parameters.put("update_ts_start", toApiFormat(updateTs));
    }

    void setTimestampStart(String timestamp) {
        parameters.put("timestamp_start", toApiFormat(timestamp));
    }

    void setTimestampEnd(String timestamp) {
        parameters.put("timestamp_end", toApiFormat(timestamp));
    }

    /**
     * Converts a CSV timestamp ({@code yyyy-MM-dd HH:mm:ss.SSS}) to the API
     * query parameter format ({@code yyyyMMddHHmmssSSS}). Passes the value
     * through unchanged if it is already compact, null, or unparseable.
     */
    static String toApiFormat(String ts) {
        if (ts == null || ts.length() != 23) return ts;
        try {
            return LocalDateTime.parse(ts, CSV_TS_FMT).format(API_TS_FMT);
        } catch (Exception e) {
            return ts;
        }
    }
}
