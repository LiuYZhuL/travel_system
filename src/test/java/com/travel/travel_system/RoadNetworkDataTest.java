package com.travel.travel_system;

import com.travel.travel_system.model.dto.RoadEdge;
import com.travel.travel_system.model.dto.RoadNetwork;
import com.travel.travel_system.service.pub.RoadNetworkService;
import com.travel.travel_system.utils.OsmPbfParser;
import com.travel.travel_system.utils.RoadNetworkCoverageInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
@SpringBootTest
public class RoadNetworkDataTest {

    @Autowired
    private RoadNetworkService roadNetworkService;
    @Test
    void testLoadRealRoadNetworkData() {
        RoadNetworkCoverageInspector inspector = new RoadNetworkCoverageInspector(roadNetworkService);
        RoadNetworkCoverageInspector.PointCoverageReport report = inspector.inspectPoint(34.14086, 113.81052, 100.0);
        System.out.println(report);

        System.out.println(inspector.inspectPoint(34.172206928242424, 113.80657972402926,50.0));
    }

}
