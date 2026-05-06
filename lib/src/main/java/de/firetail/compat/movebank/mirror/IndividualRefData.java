package de.firetail.compat.movebank.mirror;

import de.firetail.compat.movebank.api.client.Constants;

import java.util.Map;

/** Individual row as returned by the Movebank /individual endpoint. */
public final class IndividualRefData {

    private final Map<String, String> map;
    private final String individualId;
    private final String name;

    public IndividualRefData(Map<String, String> map) {
        this.map = map;
        this.individualId = map.get(Constants.Attributes.ID);
        this.name = map.get(Constants.Attributes.LOCAL_IDENTIFIER);
    }

    public Map<String, String> getMap() {
        return map;
    }

    public String getIndividualId() {
        return individualId;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "IndividualRefData{individualId='" + individualId + "', name='" + name + "'}";
    }
}
