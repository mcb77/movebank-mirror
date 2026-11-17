package de.firetail.compat.movebank.mirror.reader;

import java.util.List;

/**
 * A Movebank sensor type ("tag type"). {@link #ALL} mirrors the live Movebank
 * {@code tag_type} table (verified 2026-05-07 against the live API). The ids
 * matter: clients look sensor types up by numeric id, and the move R package
 * crosses {@code tag_type} with the events' {@code sensor_type_id}.
 * If a new type appears upstream that you need, add it here.
 */
public record SensorType(
        String id,
        String externalId,
        String name,
        boolean isLocationSensor,
        String description) {

    public static final List<SensorType> ALL = List.of(
            new SensorType("397",     "bird-ring",              "Bird Ring",              true,  ""),
            new SensorType("653",     "gps",                    "GPS",                    true,  ""),
            new SensorType("673",     "radio-transmitter",      "Radio Transmitter",      true,  ""),
            new SensorType("82798",   "argos-doppler-shift",    "Argos Doppler Shift",    true,  ""),
            new SensorType("2365682", "natural-mark",           "Natural Mark",           true,  ""),
            new SensorType("2365683", "acceleration",           "Acceleration",           false, ""),
            new SensorType("3886361", "solar-geolocator",       "Solar Geolocator",       true,  ""),
            new SensorType("7842954", "accessory-measurements", "Accessory Measurements", false, ""),
            new SensorType("9301403", "solar-geolocator-raw",   "Solar Geolocator Raw",   false, ""));
}
