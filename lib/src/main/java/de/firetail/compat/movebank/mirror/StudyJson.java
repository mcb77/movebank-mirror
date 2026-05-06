package de.firetail.compat.movebank.mirror;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * On-disk JSON DTO for one study's metadata. Fields are kept as plain maps
 * so the serialized form is a faithful, schema-free copy of the Movebank
 * responses. Use a Jackson {@code ObjectMapper} to read/write.
 */
public class StudyJson {

    public Map<String, String> study;
    public List<Map<String, String>> sensors;
    public List<Map<String, String>> tags;
    public List<Map<String, String>> deployments;
    public List<Map<String, String>> individuals;

    public Set<String> sensorTypeIds;
    public Map<String, List<String>> attributesBySensorTypeIDs;

    public StudyJson() {
        // for json mapper
    }

    public StudyJson(Study study) {
        this.study = study.study.getMap();
        this.sensorTypeIds = study.sensorTypeIds;
        this.attributesBySensorTypeIDs = study.attributesBySensorTypeIDs;

        this.sensors = new ArrayList<>();
        for (Sensor sensor : study.sensors) {
            sensors.add(sensor.getMap());
        }

        this.tags = new ArrayList<>();
        for (TagRefData tag : study.tags) {
            tags.add(tag.getMap());
        }

        this.deployments = new ArrayList<>();
        for (DeploymentRefData deployment : study.deployments) {
            deployments.add(deployment.getMap());
        }

        this.individuals = new ArrayList<>();
        for (IndividualRefData individual : study.individuals) {
            individuals.add(individual.getMap());
        }
    }
}
