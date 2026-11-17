package de.firetail.compat.movebank.mirror.reader;

import java.util.List;
import java.util.Set;

/**
 * Which events to read from one study.
 *
 * @param studyId        required
 * @param sensorTypeIds  empty: every sensor type of the study
 * @param tagId          null: every tag; combined with individuals/deployments, only their events on this tag
 * @param individualIds  non-empty: only events inside a deployment of these individuals
 * @param deploymentIds  non-empty: only events inside these deployments
 * @param from           null, or the earliest timestamp ({@code yyyy-MM-dd HH:mm:ss.SSS}, inclusive;
 *                       a prefix such as {@code 2008-07-11} works)
 * @param to             null, or the latest timestamp (inclusive, compared on the prefix's length,
 *                       so {@code 2008-08-08} includes that whole day)
 */
public record EventQuery(
        String studyId,
        List<String> sensorTypeIds,
        String tagId,
        Set<String> individualIds,
        Set<String> deploymentIds,
        String from,
        String to) {

    public EventQuery {
        if (from != null && to != null
                && (from.length() > to.length() ? from.substring(0, to.length()) : from).compareTo(to) > 0) {
            throw new IllegalArgumentException("time range starts after it ends: " + from + " / " + to);
        }
        sensorTypeIds = sensorTypeIds == null ? List.of() : List.copyOf(sensorTypeIds);
        individualIds = individualIds == null ? Set.of() : Set.copyOf(individualIds);
        deploymentIds = deploymentIds == null ? Set.of() : Set.copyOf(deploymentIds);
    }

    /** Every event of the study. */
    public static EventQuery study(String studyId) {
        return new EventQuery(studyId, null, null, null, null, null, null);
    }

    public EventQuery sensorTypes(List<String> ids) {
        return new EventQuery(studyId, ids, tagId, individualIds, deploymentIds, from, to);
    }

    public EventQuery tag(String id) {
        return new EventQuery(studyId, sensorTypeIds, id, individualIds, deploymentIds, from, to);
    }

    public EventQuery individuals(Set<String> ids) {
        return new EventQuery(studyId, sensorTypeIds, tagId, ids, deploymentIds, from, to);
    }

    public EventQuery deployments(Set<String> ids) {
        return new EventQuery(studyId, sensorTypeIds, tagId, individualIds, ids, from, to);
    }

    public EventQuery between(String from, String to) {
        return new EventQuery(studyId, sensorTypeIds, tagId, individualIds, deploymentIds, from, to);
    }

    /** True when an individual or deployment filter limits the events to selected deployments. */
    public boolean selectsDeployments() {
        return !individualIds.isEmpty() || !deploymentIds.isEmpty();
    }

    boolean inTimeRange(String ts) {
        if (from == null && to == null) return true;
        if (ts == null || ts.isEmpty()) return false;
        if (from != null && ts.compareTo(from) < 0) return false;
        if (to != null) {
            String cut = ts.length() > to.length() ? ts.substring(0, to.length()) : ts;
            if (cut.compareTo(to) > 0) return false;
        }
        return true;
    }
}
