package de.firetail.compat.movebank.mirror;

import de.firetail.compat.movebank.api.client.Constants;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.api.client.Record;
import de.firetail.compat.movebank.api.client.RequestBuilder;
import de.firetail.compat.movebank.api.client.RequestBuilderDeployment;
import de.firetail.compat.movebank.api.client.RequestBuilderIndividual;
import de.firetail.compat.movebank.api.client.RequestBuilderSensor;
import de.firetail.compat.movebank.api.client.RequestBuilderStudy;
import de.firetail.compat.movebank.api.client.RequestBuilderStudyAttribute;
import de.firetail.compat.movebank.api.client.RequestBuilderTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pulls metadata for Movebank studies into an in-memory {@link Study}
 * aggregate suitable for persisting as a {@link StudyJson} document.
 */
public class MovebankMirror {

    private static final Logger logger = LoggerFactory.getLogger(MovebankMirror.class);

    private final MovebankApiClient client;

    public MovebankMirror(MovebankApiClient client) {
        this.client = client;
    }

    public StudyRefData getStudy(String studyId) throws Exception {
        RequestBuilder request = new RequestBuilderStudy();
        request.setId(studyId);
        List<Record> studies = client.readAll(request);
        if (studies.size() != 1) {
            throw new RuntimeException("Can't get study for id " + studyId);
        }
        return new StudyRefData(recordToMap(studies.get(0)));
    }

    public Study getStudyRefData(StudyId studyId) throws Exception {

        StudyRefData study = getStudy(studyId.studyId());

        // Get sensors to extract all sensor type IDs in study
        List<Sensor> sensors = getAllSensorsForStudy(studyId.studyId());

        Set<String> tagIds = new HashSet<>();
        Set<String> sensorTypeIds = new HashSet<>();
        for (Sensor sensor : sensors) {
            logger.info("sensorId: {}", sensor.getSensorId());
            tagIds.add(sensor.getTagId());
            sensorTypeIds.add(sensor.getSensorTypeId());
        }

        logger.info("tagIds: {}", tagIds.size());
        logger.info("sensorTypeIds: {}", sensorTypeIds.size());

        List<TagRefData> tags = getAllTagRefDataForStudy(studyId.studyId());
        for (TagRefData tagRefData : tags) {
            logger.info("tagRefData: {}", tagRefData);
        }
        logger.info("tags: {}", tags.size());

        List<DeploymentRefData> deployments = getAllDeploymentRefDataForStudy(studyId.studyId());
        for (DeploymentRefData deploymentRefData : deployments) {
            logger.info("deploymentRefData: {}", deploymentRefData);
        }
        logger.info("deployments: {}", deployments.size());

        List<IndividualRefData> individuals = getAllIndividualRefDataForStudy(studyId.studyId());
        for (IndividualRefData individualRefData : individuals) {
            logger.info("individualRefData: {}", individualRefData);
        }
        logger.info("individuals: {}", individuals.size());

        Map<String, List<String>> attributesBySensorTypeIDs = new HashMap<>();
        for (String sensorTypeId : sensorTypeIds) {
            List<String> attributes = getAllStudyAttributesForStudyAndSensorType(studyId.studyId(), sensorTypeId);
            attributesBySensorTypeIDs.put(sensorTypeId, attributes);
            logger.info("attributes: {}", attributes);
        }

        logger.info("attributesBySensorTypeIDs: {}", attributesBySensorTypeIDs);

        return new Study(studyId, study, sensorTypeIds, attributesBySensorTypeIDs,
                sensors, tags, deployments, individuals);
    }

    public List<StudyId> getAllStudyIds() throws Exception {
        List<StudyId> result = new ArrayList<>();
        RequestBuilder request = new RequestBuilderStudy();
        List<Record> studies = client.readAll(request);
        for (Record study : studies) {
            String studyId = study.getStringValue(Constants.Attributes.ID);
            String studyName = study.getStringValue(Constants.Attributes.NAME);
            String licenseType = study.getStringValue("license_type");
            boolean iCanSeeData = Boolean.parseBoolean(study.getStringValue(Constants.Attributes.I_CAN_SEE_DATA));
            boolean iHaveDownloadAccess = Boolean.parseBoolean(study.getStringValue("i_have_download_access"));
            if (iCanSeeData && iHaveDownloadAccess && !"CUSTOM".equals(licenseType)) {
                result.add(new StudyId(studyId, studyName));
            }
        }
        result.sort(Comparator.comparingLong(o -> safeParseLong(o.studyId())));
        return Collections.unmodifiableList(result);
    }

    private static long safeParseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE; // push unparseable ids to the end without crashing the sort
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<String> getAllStudyAttributesForStudyAndSensorType(String studyId, String sensorTypeId) throws Exception {
        RequestBuilderStudyAttribute request = new RequestBuilderStudyAttribute(studyId, sensorTypeId);
        List<Record> records = client.readAll(request);
        logger.info("study attributes: {}", records.size());
        List<String> attributes = new ArrayList<>();
        for (Record record : records) {
            attributes.add(record.getStringValue("short_name"));
        }
        return attributes;
    }

    private List<IndividualRefData> getAllIndividualRefDataForStudy(String studyId) throws Exception {
        RequestBuilderIndividual request = new RequestBuilderIndividual(studyId);
        List<Record> records = client.readAll(request);
        logger.info("individualRefData: {}", records.size());
        List<IndividualRefData> result = new ArrayList<>();
        for (Record record : records) {
            result.add(new IndividualRefData(recordToMap(record)));
        }
        return result;
    }

    /**
     * Returns deployments enriched with the linkage attributes (id / individual_id / tag_id).
     * Movebank returns the linkage attributes only when explicitly selected, so we do two
     * passes and merge by id.
     */
    private List<DeploymentRefData> getAllDeploymentRefDataForStudy(String studyId) throws Exception {
        RequestBuilderDeployment request0 = new RequestBuilderDeployment(studyId);
        List<Record> records0 = client.readAll(request0);
        Map<String, Record> records0ById = records0.stream().collect(Collectors.toMap(
                r -> r.getStringValue("id"),
                Function.identity()));

        RequestBuilderDeployment request1 = new RequestBuilderDeployment(studyId);
        request1.addSelectAttribute("id");
        request1.addSelectAttribute("individual_id");
        request1.addSelectAttribute("tag_id");
        List<Record> records1 = client.readAll(request1);
        Map<String, Record> records1ById = records1.stream().collect(Collectors.toMap(
                r -> r.getStringValue("id"),
                Function.identity()));

        logger.info("deploymentRefData: {}", records0.size());
        List<DeploymentRefData> deployments = new ArrayList<>();
        for (String id : records0ById.keySet()) {
            Map<String, String> merged = recordToMap(records0ById.get(id));
            Record r1 = records1ById.get(id);
            if (r1 != null) {
                merged.putAll(recordToMap(r1));
            }
            deployments.add(new DeploymentRefData(merged));
        }
        return deployments;
    }

    private List<TagRefData> getAllTagRefDataForStudy(String studyId) throws Exception {
        RequestBuilderTag request = new RequestBuilderTag(studyId);
        List<Record> records = client.readAll(request);
        logger.info("tagRefData: {}", records.size());
        List<TagRefData> result = new ArrayList<>();
        for (Record record : records) {
            result.add(new TagRefData(recordToMap(record)));
        }
        return result;
    }

    private List<Sensor> getAllSensorsForStudy(String studyId) throws Exception {
        RequestBuilderSensor request = new RequestBuilderSensor(studyId);
        List<Record> sensors = client.readAll(request);
        logger.info("sensors: {}", sensors.size());
        List<Sensor> result = new ArrayList<>();
        for (Record record : sensors) {
            result.add(new Sensor(recordToMap(record)));
        }
        return result;
    }

    private static Map<String, String> recordToMap(Record record) {
        Map<String, String> map = new HashMap<>();
        for (String a : record.getAttributes()) {
            map.put(a, record.getValue(a));
        }
        return map;
    }
}
