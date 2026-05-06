package de.firetail.compat.movebank.mirror;

import de.firetail.compat.movebank.api.client.Constants;

import java.util.Map;

/** Deployment row as returned by the Movebank /deployment endpoint. */
public final class DeploymentRefData {

    private final Map<String, String> map;
    private final String deploymentId;
    private final String name;
    private final String tagId;
    private final String individualId;
    private final String deployOnTimestamp;
    private final String deployOffTimestamp;

    public DeploymentRefData(Map<String, String> map) {
        this.map = map;
        this.deploymentId = map.get(Constants.Attributes.ID);
        this.name = map.get(Constants.Attributes.LOCAL_IDENTIFIER);
        this.tagId = map.get(Constants.Attributes.TAG_ID);
        this.individualId = map.get(Constants.Attributes.INDIVIDUAL_ID);
        this.deployOnTimestamp = map.get("deploy_on_timestamp");
        this.deployOffTimestamp = map.get("deploy_off_timestamp");
    }

    public Map<String, String> getMap() {
        return map;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public String getName() {
        return name;
    }

    public String getTagId() {
        return tagId;
    }

    public String getIndividualId() {
        return individualId;
    }

    public String getDeployOnTimestamp() {
        return deployOnTimestamp;
    }

    public String getDeployOffTimestamp() {
        return deployOffTimestamp;
    }

    @Override
    public String toString() {
        return "DeploymentRefData{deploymentId='" + deploymentId + "', name='" + name
                + "', tagId='" + tagId + "', individualId='" + individualId
                + "', deployOnTimestamp='" + deployOnTimestamp
                + "', deployOffTimestamp='" + deployOffTimestamp + "'}";
    }
}
