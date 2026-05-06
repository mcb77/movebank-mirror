package de.firetail.compat.movebank.mirror;

import java.util.Collections;
import java.util.Map;

/** Raw study attribute map as returned by the Movebank /study endpoint. */
public final class StudyRefData {

    private final Map<String, String> map;

    public StudyRefData(Map<String, String> map) {
        this.map = Collections.unmodifiableMap(map);
    }

    public Map<String, String> getMap() {
        return map;
    }
}
