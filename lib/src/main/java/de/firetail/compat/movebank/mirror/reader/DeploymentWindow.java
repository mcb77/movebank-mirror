package de.firetail.compat.movebank.mirror.reader;

/**
 * A time window during which a specific tag was deployed on a specific individual.
 * <p>{@code from} and {@code to} are {@code deploy_on_timestamp} and
 * {@code deploy_off_timestamp} in Movebank timestamp format
 * ({@code yyyy-MM-dd HH:mm:ss.SSS}); null or blank means the window is open
 * on that side.
 */
public record DeploymentWindow(
        String tagId,
        String individualId,
        String deploymentId,
        String from,
        String to) {

    /**
     * Returns true if the given timestamp falls within this deployment window.
     * Comparison is lexicographic, which is correct for the fixed-width
     * {@code yyyy-MM-dd HH:mm:ss.SSS} format.
     */
    public boolean contains(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return false;
        if (from != null && !from.isBlank() && timestamp.compareTo(from) < 0) return false;
        if (to   != null && !to.isBlank()   && timestamp.compareTo(to)   > 0) return false;
        return true;
    }
}
