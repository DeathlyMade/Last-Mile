package com.lastmile.station.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lastmile.station.proto.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@GrpcService
public class StationGrpcService extends StationServiceGrpc.StationServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(StationGrpcService.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Legacy key for backward compatibility / initial load source
    private static final String REDIS_KEY_LIST = "bangalore_metro_stations";
    
    // New keys for Geo implementation
    private static final String REDIS_KEY_GEO = "stations:geo";
    private static final String REDIS_KEY_DATA = "stations:data";
    
    private static final double SEARCH_RADIUS_KM = 3.0;

    // =======================================================================
    // INIT: Data Migration / Setup
    // =======================================================================
    @PostConstruct
    public void initGeoData() {
        System.out.println("Checking Redis Geo Data...");
        
        // Check if geo key exists (or has elements)
        Long geoCount = redisTemplate.opsForZSet().size(REDIS_KEY_GEO);

        if (geoCount != null && geoCount > 0) {
            System.out.println("Geo data exists (" + geoCount + " stations). Skipping initialization.");
            return;
        }

        System.out.println("Geo data missing. Attempting migration from list: " + REDIS_KEY_LIST);
        List<String> jsonList = redisTemplate.opsForList().range(REDIS_KEY_LIST, 0, -1);

        if (jsonList == null || jsonList.isEmpty()) {
            System.err.println("No legacy data found in '" + REDIS_KEY_LIST + "'. Cannot initialize Geo data.");
            return;
        }

        for (String json : jsonList) {
            try {
                RedisStationDto dto = objectMapper.readValue(json, RedisStationDto.class);
                String stationId = dto.name; // Use name as ID

                // 1. Add to Geo Set
                redisTemplate.opsForGeo().add(REDIS_KEY_GEO, new Point(dto.location.lng, dto.location.lat), stationId);

                // 2. Add to Data Hash
                redisTemplate.opsForHash().put(REDIS_KEY_DATA, stationId, json);
                
                System.out.println("Migrated: " + stationId);

            } catch (Exception e) {
                System.err.println("Failed to migrate station: " + json);
                e.printStackTrace();
            }
        }
        System.out.println("Geo Data Initialization Complete.");
    }

    // =======================================================================
    // 1. GET STATIONS ALONG ROUTE (GeoHash Implementation)
    // =======================================================================
    @Override
    public void getStationsAlongRoute(GetStationsAlongRouteRequest request,
                                      StreamObserver<GetStationsAlongRouteResponse> responseObserver) {

        System.out.println("\n====== [GetStationsAlongRoute] START (GeoHash) ======");
        long startTime = System.currentTimeMillis();

        List<LatLng> routePoints = request.getRoutePointsList();
        Set<String> nearbyStationIds = new HashSet<>();

        // Optimization: Don't check every single point if they are very close.
        // For simplicity, we check all, but Redis is fast.
        for (LatLng point : routePoints) {
            Circle circle = new Circle(new Point(point.getLongitude(), point.getLatitude()), new Distance(SEARCH_RADIUS_KM, Metrics.KILOMETERS));
            
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().radius(REDIS_KEY_GEO, circle);

            if (results != null) {
                for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                    nearbyStationIds.add(result.getContent().getName());
                }
            }
        }

        List<Station> foundedStations = new ArrayList<>();
        if (!nearbyStationIds.isEmpty()) {
            // Bulk fetch details from Hash
            List<Object> stationsJson = redisTemplate.opsForHash().multiGet(REDIS_KEY_DATA, new ArrayList<Object>(nearbyStationIds));
            
            for (Object obj : stationsJson) {
                if (obj != null) {
                    try {
                        String json = (String) obj;
                        RedisStationDto dto = objectMapper.readValue(json, RedisStationDto.class);
                        foundedStations.add(mapToProto(dto));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        System.out.println("Found " + foundedStations.size() + " unique stations via GeoHash.");
        System.out.println("Execution Time: " + (System.currentTimeMillis() - startTime) + "ms");

        // Build Response
        GetStationsAlongRouteResponse.Builder responseBuilder = GetStationsAlongRouteResponse.newBuilder();
        
        if (foundedStations.isEmpty()) {
            responseBuilder.setSuccess(false)
                    .setMessage("No metro stations found within 3km of this route.");
        } else {
            responseBuilder.addAllStations(foundedStations);
            responseBuilder.setSuccess(true)
                    .setMessage("Found " + foundedStations.size() + " stations along the route.");
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
        System.out.println("====== [GetStationsAlongRoute] END ======\n");
    }

    // =======================================================================
    // 2. GET STATION INFO (Hash Lookup)
    // =======================================================================
    @Override
    public void getStationInfo(GetStationInfoRequest request,
                               StreamObserver<GetStationInfoResponse> responseObserver) {
        
        String requestedId = request.getStationId(); 
        System.out.println("\n====== [GetStationInfo] Looking for: " + requestedId + " ======");

        // Direct Hash Lookup O(1)
        Object jsonObj = redisTemplate.opsForHash().get(REDIS_KEY_DATA, requestedId);
        
        // Also try case-insensitive if direct match fails? 
        // Redis Hash keys are case sensitive. If we want case-insensitive, we'd need another map or scan.
        // For now, assuming exact match or user provides correct casing as per ID.
        
        GetStationInfoResponse.Builder responseBuilder = GetStationInfoResponse.newBuilder();

        if (jsonObj != null) {
            try {
                RedisStationDto dto = objectMapper.readValue((String) jsonObj, RedisStationDto.class);
                System.out.println("Station found: " + dto.name);
                responseBuilder.setStation(mapToProto(dto))
                        .setSuccess(true);
            } catch (Exception e) {
                System.err.println("Error parsing station json");
                responseBuilder.setSuccess(false);
            }
        } else {
            System.out.println("Station NOT found in Cache.");
            responseBuilder.setSuccess(false);
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
        System.out.println("====== [GetStationInfo] END ======\n");
    }

    // =======================================================================
    // 3. GET ALL STATIONS (Keys/Hash Scan)
    // =======================================================================
    @Override
    public void getAllStations(GetAllStationsRequest request,
                               StreamObserver<GetAllStationsResponse> responseObserver) {
        
        System.out.println("\n====== [GetAllStations] Fetching all... ======");
        
        List<Station> stations = new ArrayList<>();
        
        // Fetch values from Hash
        Map<Object, Object> allEntries = redisTemplate.opsForHash().entries(REDIS_KEY_DATA);
        
        for (Object val : allEntries.values()) {
             try {
                RedisStationDto dto = objectMapper.readValue((String) val, RedisStationDto.class);
                stations.add(mapToProto(dto));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        System.out.println("Returning " + stations.size() + " stations to client.");

        GetAllStationsResponse response = GetAllStationsResponse.newBuilder()
                .addAllStations(stations)
                .setSuccess(true)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
        System.out.println("====== [GetAllStations] END ======\n");
    }


    // =======================================================================
    // HELPER METHODS
    // =======================================================================

    private Station mapToProto(RedisStationDto dto) {
        return Station.newBuilder()
                .setStationId(dto.name)
                .setName(dto.name)
                .setLatitude(dto.location.lat)
                .setLongitude(dto.location.lng)
                .setLine("Namma Metro") 
                .setOrder(0)
                .build();
    }

    // Inner class to map the Redis JSON structure
    private static class RedisStationDto {
        public String name;
        public String address;
        public Location location;

        public static class Location {
            public double lat;
            public double lng;
        }
    }
}
