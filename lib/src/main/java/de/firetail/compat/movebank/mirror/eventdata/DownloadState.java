package de.firetail.compat.movebank.mirror.eventdata;

/** Per-(tag, sensor type) sync cursor and backoff state, persisted as JSON. */
public class DownloadState {

    /**
     * Catch-up cursor: the timestamp of the last record downloaded.
     * Next catch-up chunk will use this as timestamp_start (duplicates are acceptable).
     * Null means catch-up has not started yet (no timestamp filter on first chunk).
     */
    public String lastTimestamp;

    /** True once a catch-up chunk returned fewer rows than the chunk size limit. */
    public boolean catchUpComplete;

    /** Max update_ts seen across all downloaded data — cursor for update mode. */
    public String lastUpdateTs;

    /**
     * Current update polling interval in milliseconds.
     * Starts at MIN_UPDATE_INTERVAL_MS and backs off exponentially after empty update passes,
     * up to the maximum ladder rung. Resets to MIN on any pass that finds new rows.
     */
    public long updateIntervalMs = EventDataDownloader.MIN_UPDATE_INTERVAL_MS;

    /**
     * Epoch milliseconds at which the next update pass is due.
     * Zero (default) means due immediately.
     */
    public long nextUpdateDue = 0;

    public DownloadState() {
        // for JSON mapper
    }
}
