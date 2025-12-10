package com.lastmile.station.grpc;

import com.lastmile.station.proto.*;
import io.grpc.internal.testing.StreamRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StationGrpcServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private StationGrpcService stationService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void testGetStationsAlongRoute_GeoHash_Success() {
        // Mock Geo Search Results
        GeoResults<RedisGeoCommands.GeoLocation<String>> mockGeoResults = new GeoResults<>(
                Collections.singletonList(new GeoResult<>(new RedisGeoCommands.GeoLocation<>("Station A", new Point(77.5, 12.9)), new Distance(1.0)))
        );
        when(geoOperations.radius(anyString(), any(Circle.class))).thenReturn(mockGeoResults);

        // Mock Hash Get
        String stationJson = "{\"name\":\"Station A\", \"location\":{\"lat\":12.9, \"lng\":77.5}}";
        when(hashOperations.multiGet(anyString(), anyCollection())).thenReturn(Collections.singletonList(stationJson));

        // Prepare Request
        GetStationsAlongRouteRequest request = GetStationsAlongRouteRequest.newBuilder()
                .addRoutePoints(LatLng.newBuilder().setLatitude(12.91).setLongitude(77.51).build())
                .build();
        StreamRecorder<GetStationsAlongRouteResponse> responseObserver = StreamRecorder.create();

        // Execute
        stationService.getStationsAlongRoute(request, responseObserver);

        // Verify
        assertNull(responseObserver.getError());
        List<GetStationsAlongRouteResponse> results = responseObserver.getValues();
        assertEquals(1, results.size());
        assertTrue(results.get(0).getSuccess());
        assertEquals(1, results.get(0).getStationsCount());
        assertEquals("Station A", results.get(0).getStations(0).getName());
    }

    @Test
    void testGetStationInfo_Found() {
        // Mock Hash Get
        String stationJson = "{\"name\":\"Station B\", \"location\":{\"lat\":13.0, \"lng\":77.6}}";
        when(hashOperations.get(anyString(), eq("Station B"))).thenReturn(stationJson);

        // Prepare Request
        GetStationInfoRequest request = GetStationInfoRequest.newBuilder()
                .setStationId("Station B")
                .build();
        StreamRecorder<GetStationInfoResponse> responseObserver = StreamRecorder.create();

        // Execute
        stationService.getStationInfo(request, responseObserver);

        // Verify
        assertNull(responseObserver.getError());
        List<GetStationInfoResponse> results = responseObserver.getValues();
        assertEquals(1, results.size());
        assertTrue(results.get(0).getSuccess());
        assertEquals("Station B", results.get(0).getStation().getName());
    }

    @Test
    void testInitGeoData_Migration() {
        // Mock Missing Geo Data
        when(zSetOperations.size(anyString())).thenReturn(0L);

        // Mock Existing List Data
        String stationJson = "{\"name\":\"Station Migrated\", \"location\":{\"lat\":12.0, \"lng\":77.0}}";
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(Collections.singletonList(stationJson));

        // Execute
        stationService.initGeoData();

        // Verify Geo Add and Hash Put called
        verify(geoOperations).add(eq("stations:geo"), any(Point.class), eq("Station Migrated"));
        verify(hashOperations).put(eq("stations:data"), eq("Station Migrated"), eq(stationJson));
    }
}
