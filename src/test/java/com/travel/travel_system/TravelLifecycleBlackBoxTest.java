package com.travel.travel_system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.travel.travel_system.model.User;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.repository.AnchorRepository;
import com.travel.travel_system.repository.PhotoRepository;
import com.travel.travel_system.repository.PlaceSummaryMemberRepository;
import com.travel.travel_system.repository.PlaceSummaryRepository;
import com.travel.travel_system.repository.StoryBlockRepository;
import com.travel.travel_system.repository.TrackPointRepository;
import com.travel.travel_system.repository.TripAiSummaryRepository;
import com.travel.travel_system.repository.TripBBoxRepository;
import com.travel.travel_system.repository.TripNoteRepository;
import com.travel.travel_system.repository.TripRepository;
import com.travel.travel_system.repository.TripRouteSnapshotRepository;
import com.travel.travel_system.repository.TripSegmentRepository;
import com.travel.travel_system.repository.UserRepository;
import com.travel.travel_system.repository.VideoRepository;
import com.travel.travel_system.utils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "ai.api.url=http://127.0.0.1:9",
        "geocoding.amap.base-url=http://127.0.0.1:9",
        "geocoding.tencent.base-url=http://127.0.0.1:9",
        "geocoding.request-timeout-ms=100",
        "geocoding.max-retries=0",
        "logging.level.root=warn"
})
@Transactional
class TravelLifecycleBlackBoxTest {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripNoteRepository tripNoteRepository;

    @Autowired
    private TripSegmentRepository tripSegmentRepository;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AnchorRepository anchorRepository;

    @Autowired
    private PlaceSummaryRepository placeSummaryRepository;

    @Autowired
    private PlaceSummaryMemberRepository placeSummaryMemberRepository;

    @Autowired
    private StoryBlockRepository storyBlockRepository;

    @Autowired
    private TripAiSummaryRepository tripAiSummaryRepository;

    @Autowired
    private TripBBoxRepository tripBBoxRepository;

    @Autowired
    private TripRouteSnapshotRepository tripRouteSnapshotRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<Long> createdTripIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (!createdTripIds.isEmpty()) {
            List<Long> tripIds = new ArrayList<>(createdTripIds);
            Collections.reverse(tripIds);
            for (Long tripId : tripIds) {
                cleanupTrip(tripId);
            }
            createdTripIds.clear();
        }

        if (!createdUserIds.isEmpty()) {
            List<Long> userIds = new ArrayList<>(createdUserIds);
            Collections.reverse(userIds);
            for (Long userId : userIds) {
                userRepository.findById(userId).ifPresent(userRepository::delete);
            }
            createdUserIds.clear();
        }
    }

    @Test
    void shouldCompleteCoreTravelLifecycleThroughApis() throws Exception {
        User user = createTestUser("lifecycle");
        String token = jwtUtils.generateToken(user.getOpenId());

        JsonNode createTrip = requestJson(
                HttpMethod.POST,
                "/api/v1/trips",
                token,
                Map.of(
                        "title", "黑盒测试行程",
                        "timezone", "Asia/Shanghai",
                        "privacyMode", "PUBLIC",
                        "startTime", DATETIME_FORMATTER.format(LocalDateTime.now().minusMinutes(10))
                ),
                HttpStatus.OK
        );
        assertSuccess(createTrip);
        long tripId = createTrip.path("data").path("tripId").asLong();
        assertTrue(tripId > 0);
        assertEquals("ACTIVE", createTrip.path("data").path("status").asText());
        createdTripIds.add(tripId);

        JsonNode listResponse = requestJson(HttpMethod.GET, "/api/v1/trips", token, null, HttpStatus.OK);
        assertSuccess(listResponse);
        assertContainsId(listResponse.path("data").path("list"), tripId, "tripId");

        JsonNode detailResponse = requestJson(HttpMethod.GET, "/api/v1/trips/" + tripId + "/detail", token, null, HttpStatus.OK);
        assertSuccess(detailResponse);
        assertEquals(tripId, detailResponse.path("data").path("trip").path("id").asLong());
        assertEquals("ACTIVE", detailResponse.path("data").path("trip").path("status").asText());
        assertEquals("黑盒测试行程", detailResponse.path("data").path("trip").path("title").asText());

        long anchorTs = System.currentTimeMillis() - 120_000;
        JsonNode uploadTrackResponse = requestJson(
                HttpMethod.POST,
                "/api/v1/trips/" + tripId + "/track-points/batch",
                token,
                Map.of("points", buildTrackPoints(anchorTs)),
                HttpStatus.OK
        );
        assertSuccess(uploadTrackResponse);
        assertEquals(3, uploadTrackResponse.path("data").path("uploadedCount").asInt());

        JsonNode trackStatus = requestJson(HttpMethod.GET, "/api/v1/trips/" + tripId + "/track-status", token, null, HttpStatus.OK);
        assertSuccess(trackStatus);
        assertEquals(tripId, trackStatus.path("data").path("tripId").asLong());
        assertFalse(trackStatus.path("data").path("processing").asBoolean());

        JsonNode createNote = requestJson(
                HttpMethod.POST,
                "/api/v1/trips/" + tripId + "/notes",
                token,
                createNoteRequest(anchorTs),
                HttpStatus.OK
        );
        assertSuccess(createNote);
        long noteId = createNote.path("data").path("id").asLong();
        assertTrue(noteId > 0);

        JsonNode noteList = requestJson(HttpMethod.GET, "/api/v1/trips/" + tripId + "/notes", token, null, HttpStatus.OK);
        assertSuccess(noteList);
        assertContainsId(noteList.path("data"), noteId, "id");

        JsonNode updateNote = requestJson(
                HttpMethod.PATCH,
                "/api/v1/notes/" + noteId,
                token,
                updateNoteRequest(anchorTs + 60_000),
                HttpStatus.OK
        );
        assertSuccess(updateNote);
        assertEquals("午后散步-更新", updateNote.path("data").path("title").asText());
        assertEquals("MASKED", updateNote.path("data").path("privacyMode").asText());

        JsonNode updateTrip = requestJson(
                HttpMethod.PATCH,
                "/api/v1/trips/" + tripId,
                token,
                Map.of(
                        "title", "黑盒测试行程-更新",
                        "privacyMode", "MASKED"
                ),
                HttpStatus.OK
        );
        assertSuccess(updateTrip);
        assertEquals("黑盒测试行程-更新", updateTrip.path("data").path("title").asText());
        assertEquals("MASKED", updateTrip.path("data").path("privacyMode").asText());

        JsonNode pauseTrip = requestJson(HttpMethod.POST, "/api/v1/trips/" + tripId + "/pause", token, null, HttpStatus.OK);
        assertSuccess(pauseTrip);
        assertEquals("PAUSED", pauseTrip.path("data").path("status").asText());

        JsonNode pausedUpload = requestJson(
                HttpMethod.POST,
                "/api/v1/trips/" + tripId + "/track-points/batch",
                token,
                Map.of("points", buildTrackPoints(System.currentTimeMillis())),
                HttpStatus.OK
        );
        assertSuccess(pausedUpload);
        assertEquals(0, pausedUpload.path("data").path("uploadedCount").asInt());

        JsonNode resumeTrip = requestJson(HttpMethod.POST, "/api/v1/trips/" + tripId + "/resume", token, null, HttpStatus.OK);
        assertSuccess(resumeTrip);
        assertEquals("ACTIVE", resumeTrip.path("data").path("status").asText());

        JsonNode statistics = requestJson(HttpMethod.GET, "/api/v1/trips/" + tripId + "/statistics", token, null, HttpStatus.OK);
        assertSuccess(statistics);
        assertEquals(tripId, statistics.path("data").path("tripId").asLong());
        assertEquals(1, statistics.path("data").path("noteCount").asInt());

        JsonNode story = requestJson(HttpMethod.GET, "/api/v1/trips/" + tripId + "/story", token, null, HttpStatus.OK);
        assertSuccess(story);
        assertEquals(tripId, story.path("data").path("tripId").asLong());

        JsonNode report = requestJson(HttpMethod.GET, "/api/v1/trips/" + tripId + "/report", token, null, HttpStatus.OK);
        assertSuccess(report);
        assertEquals("TRIP_REPORT", report.path("data").path("reportType").asText());
        assertEquals(tripId, report.path("data").path("overview").path("tripId").asLong());

        JsonNode finishTrip = requestJson(HttpMethod.POST, "/api/v1/trips/" + tripId + "/finish", token, null, HttpStatus.OK);
        assertSuccess(finishTrip);
        assertEquals(tripId, finishTrip.path("data").path("tripId").asLong());
        assertTrue(List.of("PROCESSING", "FINISHED").contains(finishTrip.path("data").path("status").asText()));

        JsonNode detailAfterFinish = requestJson(HttpMethod.GET, "/api/v1/trips/" + tripId + "/detail", token, null, HttpStatus.OK);
        assertSuccess(detailAfterFinish);
        assertEquals(tripId, detailAfterFinish.path("data").path("trip").path("id").asLong());
        assertTrue(List.of("PROCESSING", "FINISHED").contains(detailAfterFinish.path("data").path("trip").path("status").asText()));
    }

    @Test
    void shouldRejectUnauthenticatedRequestsAndIsolateUserTrips() throws Exception {
        User owner = createTestUser("owner");
        String ownerToken = jwtUtils.generateToken(owner.getOpenId());

        JsonNode createTrip = requestJson(
                HttpMethod.POST,
                "/api/v1/trips",
                ownerToken,
                Map.of(
                        "title", "权限隔离测试",
                        "timezone", "Asia/Shanghai",
                        "privacyMode", "PRIVATE",
                        "startTime", DATETIME_FORMATTER.format(LocalDateTime.now().minusMinutes(5))
                ),
                HttpStatus.OK
        );
        assertSuccess(createTrip);
        long tripId = createTrip.path("data").path("tripId").asLong();
        createdTripIds.add(tripId);

        JsonNode unauthenticated = requestJson(HttpMethod.GET, "/api/v1/trips/" + tripId + "/detail", null, null, HttpStatus.UNAUTHORIZED);
        assertEquals("AUTH_001", unauthenticated.path("code").asText());

        User outsider = createTestUser("outsider");
        String outsiderToken = jwtUtils.generateToken(outsider.getOpenId());

        JsonNode outsiderDetail = requestJson(HttpMethod.GET, "/api/v1/trips/" + tripId + "/detail", outsiderToken, null, HttpStatus.OK);
        assertEquals("SYSTEM_500", outsiderDetail.path("code").asText());
        assertTrue(outsiderDetail.path("message").asText().contains("无权"));
    }

    private JsonNode requestJson(HttpMethod method,
                                 String path,
                                 String token,
                                 Object requestBody,
                                 HttpStatus expectedStatus) throws Exception {
        String body = requestBody == null ? "" : objectMapper.writeValueAsString(requestBody);
        HttpRequest.BodyPublisher publisher = requestBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (token != null && !token.isBlank()) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }

        HttpRequest request = builder.method(method.name(), publisher).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus.value(), response.statusCode());

        if (response.body() == null || response.body().isBlank()) {
            return NullNode.getInstance();
        }
        return objectMapper.readTree(response.body());
    }

    private void assertSuccess(JsonNode response) {
        assertEquals("0", response.path("code").asText(), "expected business success response");
    }

    private void assertContainsId(JsonNode arrayNode, long expectedId, String fieldName) {
        assertTrue(arrayNode.isArray(), "expected array payload");
        boolean found = false;
        for (JsonNode item : arrayNode) {
            long primary = item.path(fieldName).asLong();
            long fallback = item.path("id").asLong();
            if (primary == expectedId || fallback == expectedId) {
                found = true;
                break;
            }
        }
        assertTrue(found, "expected payload to contain id=" + expectedId);
    }

    private List<Map<String, Object>> buildTrackPoints(long startTs) {
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(buildTrackPoint(34.154248, 113.807683, startTs));
        points.add(buildTrackPoint(34.154455, 113.807995, startTs + 30_000));
        points.add(buildTrackPoint(34.154702, 113.808251, startTs + 60_000));
        return points;
    }

    private Map<String, Object> buildTrackPoint(double lat, double lng, long ts) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("lat", lat);
        point.put("lng", lng);
        point.put("ts", ts);
        point.put("accuracyM", 8.0);
        point.put("speedMps", 2.8);
        point.put("headingDeg", 95.0);
        point.put("coordType", "GCJ02");
        return point;
    }

    private Map<String, Object> createNoteRequest(long anchorTs) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", "午后散步");
        request.put("content", "测试一次完整旅行闭环");
        request.put("privacyMode", "PUBLIC");
        request.put("anchorTs", anchorTs);
        request.put("lat", 34.154248);
        request.put("lng", 113.807683);
        request.put("locationName", "测试地点");
        request.put("coordType", "GCJ02");
        return request;
    }

    private Map<String, Object> updateNoteRequest(long anchorTs) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", "午后散步-更新");
        request.put("content", "更新后的笔记正文");
        request.put("privacyMode", "MASKED");
        request.put("anchorTs", anchorTs);
        request.put("lat", 34.154350);
        request.put("lng", 113.807780);
        request.put("locationName", "更新地点");
        request.put("coordType", "GCJ02");
        return request;
    }

    private User createTestUser(String tag) {
        User user = new User();
        user.setOpenId("blackbox-" + tag + "-" + UUID.randomUUID());
        user.setUnionId("union-" + tag + "-" + UUID.randomUUID());
        user.setNickname("黑盒用户-" + tag);
        user.setAvatarUrl("https://example.com/avatar.png");
        user.setDefaultPrivacyMode(PrivacyMode.PUBLIC);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private void cleanupTrip(Long tripId) {
        if (tripId == null) {
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            anchorRepository.deleteByTripId(tripId);
            photoRepository.deleteByTripId(tripId);
            videoRepository.deleteByTripId(tripId);
            placeSummaryMemberRepository.deleteByTripId(tripId);
            placeSummaryRepository.deleteByTripId(tripId);
            storyBlockRepository.deleteByTripId(tripId);
            tripAiSummaryRepository.deleteByTripId(tripId);
            tripBBoxRepository.deleteByTripId(tripId);
            tripNoteRepository.deleteByTripId(tripId);
            trackPointRepository.deleteByTripId(tripId);
            tripSegmentRepository.deleteByTripId(tripId);
            tripRouteSnapshotRepository.deleteById(tripId);
            tripRepository.findById(tripId).ifPresent(tripRepository::delete);
        });
    }
}
