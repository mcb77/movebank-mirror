package de.firetail.compat.movebank.mirror;

import de.firetail.compat.movebank.api.client.Constants;

import java.util.Map;

/** Tag row as returned by the Movebank /tag endpoint. */
public final class TagRefData {

    private final Map<String, String> map;
    private final String tagId;
    private final String name;

    public TagRefData(Map<String, String> map) {
        this.map = map;
        this.tagId = map.get(Constants.Attributes.ID);
        this.name = map.get(Constants.Attributes.LOCAL_IDENTIFIER);
    }

    public Map<String, String> getMap() {
        return map;
    }

    public String getTagId() {
        return tagId;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "TagRefData{tagId='" + tagId + "', name='" + name + "'}";
    }
}
