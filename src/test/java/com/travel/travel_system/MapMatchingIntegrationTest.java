package com.travel.travel_system;

import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.model.dto.MapMatchingResult;
import com.travel.travel_system.model.dto.RoadNetwork;
import com.travel.travel_system.service.TrackPointService;
import com.travel.travel_system.service.pub.RoadNetworkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest
public class MapMatchingIntegrationTest {

    @Autowired
    private TrackPointService trackPointService;

    @Autowired
    private RoadNetworkService roadNetworkService;

    private List<TrackPoint> testTrackPoints;

    @BeforeEach
    void setUp() {
        testTrackPoints = generateMockTrackPoints();
    }

    @Test
    void testRoadNetworkLoading() {
        System.out.println("========== 测试路网加载 ==========");
        
        double testLat = 30.2741;
        double testLon = 120.1551;
        double radiusKm = 2.0;
        
        RoadNetwork roadNetwork = roadNetworkService.getRoadNetwork(testLat, testLon, radiusKm);
        
        System.out.println("路网加载成功!");
        System.out.println("节点数量: " + roadNetwork.getNodeCount());
        System.out.println("路段数量: " + roadNetwork.getEdgeCount());
        
        assert roadNetwork.getNodeCount() > 0 : "路网节点为空";
        assert roadNetwork.getEdgeCount() > 0 : "路网路段为空";
        
        System.out.println("========== 路网加载测试通过 ==========\n");
    }

    @Test
    void testCandidateRoadRetrieval() {
        System.out.println("========== 测试候选路段检索 ==========");
        
        double testLat = 30.2741;
        double testLon = 120.1551;
        double radiusKm = 2.0;
        
        RoadNetwork roadNetwork = roadNetworkService.getRoadNetwork(testLat, testLon, radiusKm);
        
        double searchRadius = 40.0;
        List<com.travel.travel_system.model.dto.RoadEdge> candidateRoads = 
            roadNetwork.findNearbyEdges(testLat, testLon, searchRadius);
        
        System.out.println("候选路段检索成功!");
        System.out.println("检索点: (" + testLat + ", " + testLon + ")");
        System.out.println("检索半径: " + searchRadius + " 米");
        System.out.println("找到候选路段数量: " + candidateRoads.size());
        
        for (int i = 0; i < Math.min(5, candidateRoads.size()); i++) {
            com.travel.travel_system.model.dto.RoadEdge road = candidateRoads.get(i);
            System.out.println("  路段 " + (i + 1) + ": ID=" + road.getId() + 
                             ", 名称=" + road.getName() + 
                             ", 方向=" + road.getDirection());
        }
        
        System.out.println("========== 候选路段检索测试通过 ==========\n");
    }

    @Test
    void testFullMapMatchingPipeline() {
        System.out.println("========== 测试完整地图匹配流程 ==========");
        
        Long testTripId = 999L;
        
        System.out.println("输入测试轨迹点数: " + testTrackPoints.size());
        System.out.println("开始执行地图匹配...");
        
        long startTime = System.currentTimeMillis();
        List<MapMatchingResult> results = trackPointService.matchTrajectory(testTripId);
        long endTime = System.currentTimeMillis();
        
        System.out.println("地图匹配完成!");
        System.out.println("耗时: " + (endTime - startTime) + " 毫秒");
        System.out.println("匹配结果数量: " + results.size());
        
        assert results.size() > 0 : "匹配结果为空";
        
        for (int i = 0; i < Math.min(10, results.size()); i++) {
            MapMatchingResult result = results.get(i);
            System.out.println("  点 " + (i + 1) + 
                             ": 位置=(" + result.getMatchedLatitude() + ", " + result.getMatchedLongitude() + ")" +
                             ", 路段=" + result.getMatchedRoadName() +
                             ", 置信度=" + String.format("%.4f", result.getConfidence()));
        }
        
        System.out.println("========== 完整地图匹配流程测试通过 ==========\n");
    }

    private List<TrackPoint> generateMockTrackPoints() {
        List<TrackPoint> points = new ArrayList<>();
        
        double baseLat = 30.2741;
        double baseLon = 120.1551;
        long baseTime = System.currentTimeMillis() - 3600000;
        
        double[][] waypoints = {
            {30.2741, 120.1551, 90.0f, 1.2f},
            {30.2738, 120.1555, 85.0f, 1.1f},
            {30.2735, 120.1560, 90.0f, 1.3f},
            {30.2732, 120.1565, 95.0f, 1.2f},
            {30.2729, 120.1570, 100.0f, 1.1f},
            {30.2726, 120.1575, 95.0f, 1.2f},
            {30.2723, 120.1580, 90.0f, 1.3f},
            {30.2720, 120.1585, 85.0f, 1.1f},
            {30.2717, 120.1590, 80.0f, 1.2f},
            {30.2714, 120.1595, 75.0f, 1.1f}
        };
        
        for (int i = 0; i < waypoints.length; i++) {
            TrackPoint point = new TrackPoint();
            point.setId((long) (i + 1));
            point.setTripId(999L);
            point.setUserId(1L);
            point.setTs(baseTime + i * 5000);
            
            double lat = waypoints[i][0];
            double lon = waypoints[i][1];
            float heading = (float) waypoints[i][2];
            float speed = (float) waypoints[i][3];
            
            point.setLatEnc(doubleToBytes(lat));
            point.setLngEnc(doubleToBytes(lon));
            point.setHeadingDeg(heading);
            point.setSpeedMps(speed);
            point.setAccuracyM(10.0f);
            
            points.add(point);
        }
        
        return points;
    }

    private byte[] doubleToBytes(double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (bits >> (i * 8));
        }
        return bytes;
    }
}
