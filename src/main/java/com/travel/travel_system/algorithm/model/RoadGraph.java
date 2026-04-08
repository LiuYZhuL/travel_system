package com.travel.travel_system.algorithm.model;
import lombok.*;

import java.io.Serializable;
import java.util.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RoadGraph implements Serializable {
    private static final long serialVersionUID = 1L;


    private final Map<Long, RoadJunction> junctions = new LinkedHashMap<>();

    private final Map<Long, RoadSegment> segments = new LinkedHashMap<>();

    private final List<RoadRestriction> restrictions = new ArrayList<>();

    private transient Map<Long, List<RoadSegment>> outgoing = new HashMap<>();
    private transient GridRoadIndex spatialIndex = new GridRoadIndex(300.0);

    private RoadGraphMetadata metadata = new RoadGraphMetadata();

    public void addJunction(RoadJunction junction) {
        if (junction != null) {
            junctions.put(junction.getId(), junction);
        }
    }

    public void addSegment(RoadSegment segment) {
        if (segment != null) {
            segments.put(segment.getSegmentId(), segment);
        }
    }

    public void addRestriction(RoadRestriction restriction) {
        if (restriction != null) {
            restrictions.add(restriction);
        }
    }

    public void buildDerivedStructures() {
        outgoing = new HashMap<>();
        spatialIndex = new GridRoadIndex(300.0);

        for (RoadJunction junction : junctions.values()) {
            junction.setDegree(0);
        }

        for (RoadSegment segment : segments.values()) {
            spatialIndex.add(segment);
            outgoing.computeIfAbsent(segment.getStartNodeId(), k -> new ArrayList<>()).add(segment);

            RoadJunction a = junctions.get(segment.getStartNodeId());
            RoadJunction b = junctions.get(segment.getEndNodeId());
            if (a != null) {
                a.increaseDegree();
            }
            if (b != null) {
                b.increaseDegree();
            }
        }

        recomputeMetadata();
    }

    public List<RoadSegment> outgoing(long nodeId) {
        if (outgoing == null) {
            buildDerivedStructures();
        }
        return outgoing.getOrDefault(nodeId, Collections.emptyList());
    }

    public List<RoadSegment> nearbySegments(double lat, double lon, double radiusMeters) {
        if (spatialIndex == null) {
            buildDerivedStructures();
        }
        List<RoadSegment> coarse = spatialIndex.query(lat, lon, radiusMeters);
        List<RoadSegment> result = new ArrayList<>();
        for (RoadSegment segment : coarse) {
            if (segment.distanceTo(lat, lon) <= radiusMeters) {
                result.add(segment);
            }
        }
        return result;
    }

    private void recomputeMetadata() {
        metadata.setJunctionCount(junctions.size());
        metadata.setSegmentCount(segments.size());
        metadata.setRestrictionCount(restrictions.size());

        double totalLength = 0.0;
        for (RoadSegment segment : segments.values()) {
            totalLength += segment.getLengthMeters();
        }
        metadata.setTotalLengthMeters(totalLength);

        WeakStats stats = computeWeakConnectivity();
        metadata.setWeakComponents(stats.components);
        metadata.setLargestWeakComponentRatio(stats.totalNodes <= 0 ? 1.0 : stats.largest / (double) stats.totalNodes);
    }

    private WeakStats computeWeakConnectivity() {
        if (junctions.isEmpty()) {
            return new WeakStats(0, 0, 0);
        }
        Map<Long, Set<Long>> undirected = new HashMap<>();
        for (Long nodeId : junctions.keySet()) {
            undirected.put(nodeId, new LinkedHashSet<>());
        }
        for (RoadSegment segment : segments.values()) {
            undirected.computeIfAbsent(segment.getStartNodeId(), k -> new LinkedHashSet<>()).add(segment.getEndNodeId());
            undirected.computeIfAbsent(segment.getEndNodeId(), k -> new LinkedHashSet<>()).add(segment.getStartNodeId());
        }

        int components = 0;
        int largest = 0;
        Set<Long> visited = new HashSet<>();
        for (Long nodeId : undirected.keySet()) {
            if (!visited.add(nodeId)) {
                continue;
            }
            components++;
            int size = 0;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(nodeId);
            while (!queue.isEmpty()) {
                long current = queue.poll();
                size++;
                for (Long next : undirected.getOrDefault(current, Collections.emptySet())) {
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            largest = Math.max(largest, size);
        }
        return new WeakStats(components, largest, undirected.size());
    }

    private static class WeakStats {
        final int components;
        final int largest;
        final int totalNodes;

        private WeakStats(int components, int largest, int totalNodes) {
            this.components = components;
            this.largest = largest;
            this.totalNodes = totalNodes;
        }
    }
}
