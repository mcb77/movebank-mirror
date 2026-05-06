package de.firetail.compat.movebank.mirror;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory aggregate of one study's metadata as fetched from Movebank. */
public final class Study {

    public final StudyId studyId;
    public final StudyRefData study;
    public final Set<String> sensorTypeIds;
    public final Map<String, List<String>> attributesBySensorTypeIDs;
    public final List<Sensor> sensors;
    public final List<TagRefData> tags;
    public final List<DeploymentRefData> deployments;
    public final List<IndividualRefData> individuals;

    public Study(
            StudyId studyId,
            StudyRefData study,
            Set<String> sensorTypeIds,
            Map<String, List<String>> attributesBySensorTypeIDs,
            List<Sensor> sensors,
            List<TagRefData> tags,
            List<DeploymentRefData> deployments,
            List<IndividualRefData> individuals) {
        this.studyId = studyId;
        this.study = study;
        this.sensorTypeIds = sensorTypeIds;
        this.attributesBySensorTypeIDs = attributesBySensorTypeIDs;
        this.sensors = sensors;
        this.tags = tags;
        this.deployments = deployments;
        this.individuals = individuals;
    }
}
