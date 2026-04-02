package com.travel.travel_system;

import com.travel.travel_system.model.dto.RoadEdge;
import com.travel.travel_system.model.dto.RoadNetwork;
import com.travel.travel_system.model.dto.RoadNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MapMatchingAlgorithmTest {

    private RoadNetwork testRoadNetwork;

    @BeforeEach
    void setUp() {
        testRoadNetwork = createSimpleTestRoadNetwork();
    }

    @Test
    void testRoadNetworkCreation() {
        System.out.println("========== 测试路网创建 ==========");
        
        assertNotNull(testRoadNetwork);
        assertEquals(4, testRoadNetwork.getNodeCount());
        assertEquals(3, testRoadNetwork.getEdgeCount());
        
        System.out.println("路网创建成功!");
        System.out.println("节点数量: " + testRoadNetwork.getNodeCount());
        System.out.println("路段数量: " + testRoadNetwork.getEdgeCount());
        System.out.println("========== 路网创建测试通过 ==========\n");
    }

    @Test
    void testCandidateRoadRetrieval() {
        System.out.println("========== 测试候选路段检索 ==========");
        
        double testLat = 30.2742;
        double testLon = 120.1552;
        double searchRadius = 100.0;
        
        List<RoadEdge> candidateRoads = testRoadNetwork.findNearbyEdges(testLat, testLon, searchRadius);
        
        System.out.println("检索点: (" + testLat + ", " + testLon + ")");
        System.out.println("检索半径: " + searchRadius + " 米");
        System.out.println("找到候选路段数量: " + candidateRoads.size());
        
        assertFalse(candidateRoads.isEmpty(), "应该找到至少一条候选路段");
        
        for (int i = 0; i < candidateRoads.size(); i++) {
            RoadEdge road = candidateRoads.get(i);
            System.out.println("  候选路段 " + (i + 1) + 
                             ": ID=" + road.getId() + 
                             ", 名称=" + road.getName());
        }
        
        System.out.println("========== 候选路段检索测试通过 ==========\n");
    }

    @Test
    void testRouteDistanceCalculation() {
        System.out.println("========== 测试路径距离计算 ==========");
        
        RoadEdge edge1 = testRoadNetwork.getEdge(1);
        RoadEdge edge2 = testRoadNetwork.getEdge(2);
        
        assertNotNull(edge1);
        assertNotNull(edge2);
        
        double distance = testRoadNetwork.calculateRouteDistance(edge1, edge2);
        
        System.out.println("路段1: " + edge1.getName());
        System.out.println("路段2: " + edge2.getName());
        System.out.println("计算距离: " + String.format("%.2f", distance) + " 米");
        
        assertTrue(distance > 0, "距离应该大于0");
        
        System.out.println("========== 路径距离计算测试通过 ==========\n");
    }

    @Test
    void testDistanceToRoadCalculation() {
        System.out.println("========== 测试点到路段距离计算 ==========");
        
        RoadEdge testEdge = testRoadNetwork.getEdge(1);
        assertNotNull(testEdge);
        
        double testLat = 30.2740;
        double testLon = 120.1550;
        
        double distance = calculateDistanceToRoad(testLat, testLon, testEdge);
        
        System.out.println("测试点: (" + testLat + ", " + testLon + ")");
        System.out.println("目标路段: " + testEdge.getName());
        System.out.println("计算距离: " + String.format("%.2f", distance) + " 米");
        
        assertTrue(distance >= 0, "距离不能为负数");
        
        System.out.println("========== 点到路段距离计算测试通过 ==========\n");
    }

    @Test
    void testProjectionCalculation() {
        System.out.println("========== 测试投影点计算 ==========");
        
        RoadEdge testEdge = testRoadNetwork.getEdge(1);
        assertNotNull(testEdge);
        
        double testLat = 30.2740;
        double testLon = 120.1550;
        
        double[] projection = projectPointToRoad(testLat, testLon, testEdge);
        
        System.out.println("原始点: (" + testLat + ", " + testLon + ")");
        System.out.println("投影点: (" + projection[0] + ", " + projection[1] + ")");
        
        assertNotNull(projection);
        assertEquals(2, projection.length);
        
        System.out.println("========== 投影点计算测试通过 ==========\n");
    }

    private RoadNetwork createSimpleTestRoadNetwork() {
        RoadNetwork network = new RoadNetwork();
        
        RoadNode node1 = new RoadNode();
        node1.setId(1L);
        node1.setLat(30.2741);
        node1.setLon(120.1551);
        node1.setDegree(2);
        
        RoadNode node2 = new RoadNode();
        node2.setId(2L);
        node2.setLat(30.2745);
        node2.setLon(120.1560);
        node2.setDegree(3);
        
        RoadNode node3 = new RoadNode();
        node3.setId(3L);
        node3.setLat(30.2738);
        node3.setLon(120.1565);
        node3.setDegree(2);
        
        RoadNode node4 = new RoadNode();
        node4.setId(4L);
        node4.setLat(30.2750);
        node4.setLon(120.1570);
        node4.setDegree(1);
        
        network.addNode(node1);
        network.addNode(node2);
        network.addNode(node3);
        network.addNode(node4);
        
        RoadEdge edge1 = new RoadEdge(1L, node1.getLat(), node1.getLon(), node2.getLat(), node2.getLon());
        edge1.setName("测试道路1");
        edge1.setStartNodeId(node1.getId());
        edge1.setEndNodeId(node2.getId());
        edge1.setNodeDegree(2);
        
        RoadEdge edge2 = new RoadEdge(2L, node2.getLat(), node2.getLon(), node3.getLat(), node3.getLon());
        edge2.setName("测试道路2");
        edge2.setStartNodeId(node2.getId());
        edge2.setEndNodeId(node3.getId());
        edge2.setNodeDegree(3);
        
        RoadEdge edge3 = new RoadEdge(3L, node2.getLat(), node2.getLon(), node4.getLat(), node4.getLon());
        edge3.setName("测试道路3");
        edge3.setStartNodeId(node2.getId());
        edge3.setEndNodeId(node4.getId());
        edge3.setNodeDegree(2);
        
        network.addEdge(edge1);
        network.addEdge(edge2);
        network.addEdge(edge3);
        
        return network;
    }

    private double calculateDistanceToRoad(double lat, double lon, RoadEdge road) {
        double lat1 = lat;
        double lon1 = lon;
        double lat2 = road.getStartLat();
        double lon2 = road.getStartLon();
        double lat3 = road.getEndLat();
        double lon3 = road.getEndLon();

        double A = lat1 - lat2;
        double B = lon1 - lon2;
        double C = lat3 - lat2;
        double D = lon3 - lon2;

        double dot = A * C + B * D;
        double lenSq = C * C + D * D;
        double param = dot / lenSq;

        double xx, yy;
        if (param < 0) {
            xx = lat2;
            yy = lon2;
        } else if (param > 1) {
            xx = lat3;
            yy = lon3;
        } else {
            xx = lat2 + param * C;
            yy = lon2 + param * D;
        }

        double dx = lat1 - xx;
        double dy = lon1 - yy;
        return Math.sqrt(dx * dx + dy * dy) * 111000;
    }

    private double[] projectPointToRoad(double lat, double lon, RoadEdge road) {
        double lat1 = lat;
        double lon1 = lon;
        double lat2 = road.getStartLat();
        double lon2 = road.getStartLon();
        double lat3 = road.getEndLat();
        double lon3 = road.getEndLon();

        double A = lat1 - lat2;
        double B = lon1 - lon2;
        double C = lat3 - lat2;
        double D = lon3 - lon2;

        double dot = A * C + B * D;
        double lenSq = C * C + D * D;
        double param = dot / lenSq;

        double xx, yy;
        if (param < 0) {
            xx = lat2;
            yy = lon2;
        } else if (param > 1) {
            xx = lat3;
            yy = lon3;
        } else {
            xx = lat2 + param * C;
            yy = lon2 + param * D;
        }

        return new double[]{xx, yy};
    }
}
