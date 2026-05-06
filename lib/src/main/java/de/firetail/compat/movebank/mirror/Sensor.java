package de.firetail.compat.movebank.mirror;

import de.firetail.compat.movebank.api.client.Constants;

import java.util.Map;

/** Sensor row (id, sensor_type_id, tag_id) as returned by the Movebank /sensor endpoint. */
public final class Sensor {

    private final Map<String, String> map;
    private final String sensorId;
    private final String sensorTypeId;
    private final String tagId;

    public Sensor(Map<String, String> map) {
        this.map = map;
        this.sensorId = map.get(Constants.Attributes.ID);
        this.sensorTypeId = map.get(Constants.Attributes.SENSOR_TYPE_ID);
        this.tagId = map.get(Constants.Attributes.TAG_ID);
    }

    public String getSensorId() {
        return sensorId;
    }

    public String getSensorTypeId() {
        return sensorTypeId;
    }

    public String getTagId() {
        return tagId;
    }

    public Map<String, String> getMap() {
        return map;
    }

    @Override
    public String toString() {
        return "Sensor{sensorId='" + sensorId + "', sensorTypeId='" + sensorTypeId
                + "', tagId='" + tagId + "'}";
    }
}
