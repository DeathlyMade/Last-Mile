package com.lastmile.location.grpc;

import com.lastmile.location.proto.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.*;
import java.util.stream.Collectors;

@GrpcService
public class LocationGrpcService extends LocationServiceGrpc.LocationServiceImplBase {
    
    private static final Logger log = LoggerFactory.getLogger(LocationGrpcService.class);
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;
    
    private static final double EARTH_RADIUS_KM = 6371.0;
    
    @Override
    public void updateLocation(UpdateLocationRequest request,
                              StreamObserver<UpdateLocationResponse> responseObserver) {
        String driverId = request.getDriverId();
        double latitude = request.getLatitude();
        double longitude = request.getLongitude();
        
        String key = "location:" + driverId;
        Map<String, String> locationData = new HashMap<>();
        locationData.put("latitude", String.valueOf(latitude));
        locationData.put("longitude", String.valueOf(longitude));
        locationData.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        redisTemplate.opsForHash().putAll(key, locationData);
        redisTemplate.expire(key, java.time.Duration.ofHours(1));

        // Publish to Redis Channel for streaming
        String channel = "driver-location:" + driverId;
        String message = latitude + "," + longitude;
        redisTemplate.convertAndSend(channel, message);
        
        UpdateLocationResponse response = UpdateLocationResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Location updated successfully")
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void monitorDriverLocation(MonitorDriverLocationRequest request,
                                      StreamObserver<DriverLocation> responseObserver) {
        String driverId = request.getDriverId();
        String channel = "driver-location:" + driverId;
        
        io.grpc.stub.ServerCallStreamObserver<DriverLocation> serverObserver = 
            (io.grpc.stub.ServerCallStreamObserver<DriverLocation>) responseObserver;

        org.springframework.data.redis.connection.MessageListener listener = (message, pattern) -> {
            String body = new String(message.getBody());
            String[] parts = body.split(",");
            if (parts.length == 2) {
                try {
                    double lat = Double.parseDouble(parts[0]);
                    double lon = Double.parseDouble(parts[1]);
                    DriverLocation location = DriverLocation.newBuilder()
                            .setDriverId(driverId)
                            .setLatitude(lat)
                            .setLongitude(lon)
                            .build();
                    synchronized (responseObserver) {
                        responseObserver.onNext(location);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        redisMessageListenerContainer.addMessageListener(listener, new org.springframework.data.redis.listener.ChannelTopic(channel));

        serverObserver.setOnCancelHandler(() -> {
            redisMessageListenerContainer.removeMessageListener(listener);
        });
    }
    
    @Override
    public void getLocation(GetLocationRequest request,
                          StreamObserver<GetLocationResponse> responseObserver) {
        String driverId = request.getDriverId();
        String key = "location:" + driverId;
        Map<Object, Object> locationData = redisTemplate.opsForHash().entries(key);
        
        GetLocationResponse.Builder responseBuilder = GetLocationResponse.newBuilder();
        
        if (locationData.isEmpty()) {
            responseBuilder.setSuccess(false);
        } else {
            double latitude = Double.parseDouble((String) locationData.get("latitude"));
            double longitude = Double.parseDouble((String) locationData.get("longitude"));
            long timestamp = Long.parseLong((String) locationData.get("timestamp"));
            
            responseBuilder.setLatitude(latitude)
                    .setLongitude(longitude)
                    .setTimestamp(timestamp)
                    .setSuccess(true);
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void findNearbyDrivers(FindNearbyDriversRequest request,
                                 StreamObserver<FindNearbyDriversResponse> responseObserver) {
        double latitude = request.getLatitude();
        double longitude = request.getLongitude();
        double radiusKm = request.getRadiusKm();
        
        List<DriverLocation> nearbyDrivers = new ArrayList<>();
        
        Set<String> keys = redisTemplate.keys("location:*");
        if (keys != null) {
            for (String key : keys) {
                String driverId = key.replace("location:", "");
                Map<Object, Object> locationData = redisTemplate.opsForHash().entries(key);
                
                if (!locationData.isEmpty()) {
                    double driverLat = Double.parseDouble((String) locationData.get("latitude"));
                    double driverLon = Double.parseDouble((String) locationData.get("longitude"));
                    
                    double distance = calculateDistance(latitude, longitude, driverLat, driverLon);
                    if (distance <= radiusKm) {
                        DriverLocation driverLocation = DriverLocation.newBuilder()
                                .setDriverId(driverId)
                                .setLatitude(driverLat)
                                .setLongitude(driverLon)
                                .setDistanceKm(distance)
                                .build();
                        nearbyDrivers.add(driverLocation);
                    }
                }
            }
        }
        
        nearbyDrivers.sort(Comparator.comparingDouble(DriverLocation::getDistanceKm));
        
        FindNearbyDriversResponse response = FindNearbyDriversResponse.newBuilder()
                .addAllDrivers(nearbyDrivers)
                .setSuccess(true)
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}

