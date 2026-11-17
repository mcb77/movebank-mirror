package de.firetail.compat.movebank.mirror.reader;

/**
 * One event row as stored in the mirror, with what the file layout and the
 * deployment table add to it. Valid only during the
 * {@link EventVisitor#event} call that receives it.
 */
public final class Event {

    private final EventColumns columns;
    private final String[] values;
    private final String tagId;
    private final String sensorTypeId;
    private final String timestamp;
    private final DeploymentWindow deployment;

    Event(EventColumns columns, String[] values, String tagId, String sensorTypeId,
          String timestamp, DeploymentWindow deployment) {
        this.columns = columns;
        this.values = values;
        this.tagId = tagId;
        this.sensorTypeId = sensorTypeId;
        this.timestamp = timestamp;
        this.deployment = deployment;
    }

    /** The column names of the file this row came from. */
    public EventColumns columns() { return columns; }

    /** The raw values, aligned with {@link #columns()}; may be shorter than the header. */
    public String[] values() { return values; }

    /** The value of a source column, or null if the file has no such column. */
    public String get(String column) {
        int i = columns.indexOf(column);
        return i >= 0 && i < values.length ? values[i] : null;
    }

    /** The tag directory the row came from. */
    public String tagId() { return tagId; }

    public String sensorTypeId() { return sensorTypeId; }

    /** The {@code timestamp} column, or null. */
    public String timestamp() { return timestamp; }

    /**
     * The deployment the event falls in: the first deployment of its tag whose
     * window contains the timestamp. Null for undeployed events.
     */
    public DeploymentWindow deployment() { return deployment; }
}
