package com.lastmile.driver.grpc;

import com.lastmile.driver.model.Driver;
import com.lastmile.driver.proto.*;
import com.lastmile.driver.repository.DriverRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.*;

@GrpcService
public class DriverGrpcService extends DriverServiceGrpc.DriverServiceImplBase {
    
    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    
    @Override
    public void registerRoute(RegisterRouteRequest request,
                             StreamObserver<RegisterRouteResponse> responseObserver) {
        String driverId = request.getDriverId();
        String destination = request.getDestination();
        int availableSeats = request.getAvailableSeats();
        List<String> metroStations = new ArrayList<>(request.getMetroStationsList());
        // Use atomic update to prevent overwriting location
        Query query = new Query(Criteria.where("_id").is(driverId));
        Update update = new Update()
            .set("routeId", UUID.randomUUID().toString())
            .set("destination", destination)
            .set("availableSeats", availableSeats)
            .set("metroStations", metroStations);
            
        mongoTemplate.upsert(query, update, Driver.class);
        
        // Fetch fresh driver to return routeID (technically we just generated it, but good to be consistent)
        Driver driver = driverRepository.findById(driverId).orElse(new Driver()); 

        // If driver was just made by upsert, ensure ID is set (Mongo might do this but explicit is safe for object ref)
        driver.setDriverId(driverId);
        
        RegisterRouteResponse response = RegisterRouteResponse.newBuilder()
                .setRouteId(driver.getRouteId())
                .setSuccess(true)
                .setMessage("Route registered successfully")
                .setMessage("Route registered successfully")
                .build();
        
        // Notify Matching Service about new driver availability
        try {
            String token = AuthInterceptor.AUTH_TOKEN_KEY.get();
            String event = "DRIVER_AVAILABLE," + driverId + "," + (token != null ? token : "");
            redisTemplate.convertAndSend("driver-events", event);
            System.out.println("DEBUG: Published DRIVER_AVAILABLE event: " + event);
        } catch (Exception e) {
            System.err.println("Failed to publish driver availability event: " + e.getMessage());
        }
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void updateLocation(UpdateLocationRequest request,
                              StreamObserver<UpdateLocationResponse> responseObserver) {
        String driverId = request.getDriverId();
        double latitude = request.getLatitude();
        double longitude = request.getLongitude();
        
        Query query = new Query(Criteria.where("_id").is(driverId));
        Update update = new Update()
                .set("currentLocation.latitude", latitude)
                .set("currentLocation.longitude", longitude)
                .set("currentLocation.timestamp", System.currentTimeMillis());
        
        long modifiedCount = mongoTemplate.upsert(query, update, Driver.class).getModifiedCount();
        
        if (modifiedCount == 0 && !driverRepository.existsById(driverId)) {
             UpdateLocationResponse response = UpdateLocationResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Driver not found")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }
        
        UpdateLocationResponse response = UpdateLocationResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Location updated successfully")
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void acceptTrip(AcceptTripRequest request, StreamObserver<AcceptTripResponse> responseObserver) {
        String driverId = request.getDriverId();
        
        Driver.TripRecord record = new Driver.TripRecord();
        record.setTripId(request.getTripId());
        record.setRiderId(request.getRiderId());
        record.setRiderName(request.getRiderName());
        record.setRiderRating(request.getRiderRating());
        record.setPickupStation(request.getPickupStation());
        record.setDestination(request.getDestination());
        record.setStatus("scheduled");
        record.setFare(request.getFare());
        
        Query query = new Query(Criteria.where("_id").is(driverId).and("availableSeats").gt(0));
        Update update = new Update()
                .push("activeTrips", record)
                .inc("availableSeats", -1);
        
        long modifiedCount = mongoTemplate.updateFirst(query, update, Driver.class).getModifiedCount();
        
        AcceptTripResponse response = AcceptTripResponse.newBuilder()
                .setSuccess(modifiedCount > 0)
                .setMessage(modifiedCount > 0 ? "Trip accepted" : "Driver not found or no seats available")
                .build();
        
        // write debug statements
        System.out.println("Driver " + driverId + " accepted trip " + request.getTripId());
        // print updated available seats
        Driver driver = driverRepository.findById(driverId).orElse(null);
        if (driver != null) {
            System.out.println("Updated available seats: " + driver.getAvailableSeats());
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void startTrip(StartTripRequest request, StreamObserver<StartTripResponse> responseObserver) {
        String driverId = request.getDriverId();
        String tripId = request.getTripId();

        Query query = new Query(Criteria.where("_id").is(driverId)
                .and("activeTrips.tripId").is(tripId));
        Update update = new Update()
                .set("activeTrips.$.status", "active")
                .set("activeTrips.$.pickupTimestamp", System.currentTimeMillis());

        long modifiedCount = mongoTemplate.updateFirst(query, update, Driver.class).getModifiedCount();

        StartTripResponse response = StartTripResponse.newBuilder()
                .setSuccess(modifiedCount > 0)
                .setMessage(modifiedCount > 0 ? "Trip started" : "Trip not found")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override 
    public void completeActiveTrip(CompleteActiveTripRequest request, StreamObserver<CompleteActiveTripResponse> responseObserver) {
        String driverId = request.getDriverId();
        String tripId = request.getTripId();

        Driver driver = driverRepository.findById(driverId).orElse(null);
        if (driver == null) {
             CompleteActiveTripResponse response = CompleteActiveTripResponse.newBuilder()
                .setSuccess(false).setMessage("Driver not found").build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }


        Driver.TripRecord tripToMove = null;
        if (driver.getActiveTrips() != null) {
            for (Driver.TripRecord t : driver.getActiveTrips()) {
                if (t.getTripId().equals(tripId)) {
                    tripToMove = t;
                    break;
                }
            }
        }

        if (tripToMove == null) {
             CompleteActiveTripResponse response = CompleteActiveTripResponse.newBuilder()
                .setSuccess(false).setMessage("Trip not found in active trips").build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }


        tripToMove.setStatus("completed");
        tripToMove.setDropoffTimestamp(System.currentTimeMillis());

        Query query = new Query(Criteria.where("_id").is(driverId).and("activeTrips.tripId").is(tripId));
        Update update = new Update()
                .pull("activeTrips", Query.query(Criteria.where("tripId").is(tripId)))
                .push("rideHistory", tripToMove)
                .inc("totalEarnings", tripToMove.getFare())
                .inc("availableSeats", 1);

        long modifiedCount = mongoTemplate.updateFirst(query, update, Driver.class).getModifiedCount();

        // --------------------------------------------------------------------------
        // NEW: Publish Driver Available Event so Matching Service can process pending
        // --------------------------------------------------------------------------
        if (modifiedCount > 0) {
            try {
                // Since this is a grpc call, we might not have the auth token easily available 
                // unless we extracted it from Context or it was passed.
                // For internal events, we might not strictly need the token if the listener is internal,
                // but the listener uses it to call other services.
                // We'll try to get it from the current context.
                String token = AuthInterceptor.AUTH_TOKEN_KEY.get();
                String event = "DRIVER_AVAILABLE," + driverId + "," + (token != null ? token : "");
                redisTemplate.convertAndSend("driver-events", event);
                System.out.println("DEBUG: Published DRIVER_AVAILABLE event after trip completion: " + event);
            } catch (Exception e) {
                System.err.println("Failed to publish driver availability event: " + e.getMessage());
            }
        }
        // --------------------------------------------------------------------------

        CompleteActiveTripResponse response = CompleteActiveTripResponse.newBuilder()
                .setSuccess(modifiedCount > 0)
                .setMessage(modifiedCount > 0 ? "Trip completed successfully" : "Trip state changed concurrently")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    

    @Override
    public void getDriverInfo(GetDriverInfoRequest request,
                              StreamObserver<GetDriverInfoResponse> responseObserver) {
        String driverId = request.getDriverId();
        
        Driver driver = driverRepository.findById(driverId)
                .orElse(null);
        
        GetDriverInfoResponse.Builder responseBuilder = GetDriverInfoResponse.newBuilder();
        
        if (driver == null) {
            responseBuilder.setSuccess(false);
        } else {
            responseBuilder.setDriverId(driver.getDriverId())
                    .setDestination(driver.getDestination())
                    .setAvailableSeats(driver.getAvailableSeats())
                    .addAllMetroStations(driver.getMetroStations())
                    .setRating(driver.getRating())
                    .setSuccess(true);
            
            if (driver.getCurrentLocation() != null) {
                Location location = Location.newBuilder()
                        .setLatitude(driver.getCurrentLocation().getLatitude())
                        .setLongitude(driver.getCurrentLocation().getLongitude())
                        .setTimestamp(driver.getCurrentLocation().getTimestamp())
                        .build();
                responseBuilder.setCurrentLocation(location);
            }
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void listDrivers(ListDriversRequest request,
                           StreamObserver<ListDriversResponse> responseObserver) {
        String station = request.getStation();
        List<Driver> drivers;
        
        if (station == null || station.isEmpty()) {
             drivers = driverRepository.findAll();
        } else {
             // Optimized: Use DB query instead of filtering in memory
             drivers = driverRepository.findByMetroStationsContaining(station);
        }
        
        List<DriverInfo> driverInfos = drivers.stream().map(driver -> {
            DriverInfo.Builder infoBuilder = DriverInfo.newBuilder()
                    .setDriverId(driver.getDriverId())
                    .setDestination(driver.getDestination() != null ? driver.getDestination() : "")
                    .setAvailableSeats(driver.getAvailableSeats())
                    .addAllMetroStations(driver.getMetroStations() != null ? driver.getMetroStations() : Collections.emptyList())
                    .setRating(driver.getRating());

            if (driver.getCurrentLocation() != null) {
                Location location = Location.newBuilder()
                        .setLatitude(driver.getCurrentLocation().getLatitude())
                        .setLongitude(driver.getCurrentLocation().getLongitude())
                        .setTimestamp(driver.getCurrentLocation().getTimestamp())
                        .build();
                infoBuilder.setCurrentLocation(location);
            }
            return infoBuilder.build();
        }).toList();
        
        ListDriversResponse response = ListDriversResponse.newBuilder()
                .addAllDrivers(driverInfos)
                .setSuccess(true)
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


    @Override
    public void getDriverDashboard(GetDriverDashboardRequest request, StreamObserver<GetDriverDashboardResponse> responseObserver) {
        String driverId = request.getDriverId();
        Driver driver = driverRepository.findById(driverId).orElse(null);
        GetDriverDashboardResponse.Builder b = GetDriverDashboardResponse.newBuilder();
        if (driver == null) {
            b.setSuccess(false);
            responseObserver.onNext(b.build());
            responseObserver.onCompleted();
            return;
        }


        if (driver.getActiveTrips() == null) driver.setActiveTrips(new ArrayList<>());
        if (driver.getRideHistory() == null) driver.setRideHistory(new ArrayList<>());


        int totalEarnings = driver.getTotalEarnings();
        if (totalEarnings == 0 && !driver.getRideHistory().isEmpty()) {
            totalEarnings = driver.getRideHistory().stream().mapToInt(Driver.TripRecord::getFare).sum();
        }

        double computedRating = driver.getRating();
        if (!driver.getRideHistory().isEmpty()) {
            double avgRating = driver.getRideHistory().stream()
                .filter(t -> t.getDriverRatingReceived() > 0)
                .mapToInt(Driver.TripRecord::getDriverRatingReceived)
                .average()
                .orElse(0.0);
            
            if (avgRating > 0) {
                computedRating = Math.round(avgRating * 100.0) / 100.0;
            }
        }

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate yesterday = today.minusDays(1);
        int todayEarnings = 0;
        int yesterdayEarnings = 0;
        for (Driver.TripRecord rec : driver.getRideHistory()) {
            LocalDate tripDay = Instant.ofEpochMilli(rec.getDropoffTimestamp() == 0 ? rec.getPickupTimestamp() : rec.getDropoffTimestamp())
                    .atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
            if (tripDay.equals(today)) todayEarnings += rec.getFare();
            else if (tripDay.equals(yesterday)) yesterdayEarnings += rec.getFare();
        }

        for (Driver.TripRecord rec : driver.getActiveTrips()) {
            TripInfo info = TripInfo.newBuilder()
                    .setTripId(rec.getTripId())
                    .setRiderId(rec.getRiderId() != null ? rec.getRiderId() : "")
                    .setRiderName(rec.getRiderName())
                    .setRiderRating(rec.getRiderRating())
                    .setPickupStation(rec.getPickupStation())
                    .setDestination(rec.getDestination())
                    .setStatus(rec.getStatus())
                    .setFare(rec.getFare())
                    .setPickupTimestamp(rec.getPickupTimestamp())
                    .build();
            b.addActiveTrips(info);
        }

        for (Driver.TripRecord rec : driver.getRideHistory()) {
            RideHistoryItem item = RideHistoryItem.newBuilder()
                    .setTripId(rec.getTripId())
                    .setDate(Instant.ofEpochMilli(rec.getPickupTimestamp()).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString())
                    .setRiderName(rec.getRiderName())
                    .setDestination(rec.getDestination())
                    .setFare(rec.getFare())
                    .setRatingGiven(rec.getRiderRatingGiven())
                    .setPickupTimestamp(rec.getPickupTimestamp())
                    .setDropoffTimestamp(rec.getDropoffTimestamp())
                    .build();
            b.addRideHistory(item);
        }

        b.setSuccess(true)
                .setDriverId(driver.getDriverId())
                .setDriverRating(computedRating)
                .setTotalEarnings(totalEarnings)
                .setTodayEarnings(todayEarnings)
                .setYesterdayEarnings(yesterdayEarnings)
                .setDestination(driver.getDestination() == null ? "" : driver.getDestination())
                .setAvailableSeats(driver.getAvailableSeats())
                .addAllMetroStations(driver.getMetroStations() == null ? List.of() : driver.getMetroStations());

        if (driver.getCurrentLocation() != null) {
            Location l = Location.newBuilder()
                    .setLatitude(driver.getCurrentLocation().getLatitude())
                    .setLongitude(driver.getCurrentLocation().getLongitude())
                    .setTimestamp(driver.getCurrentLocation().getTimestamp())
                    .build();
            b.setCurrentLocation(l);
        }

        responseObserver.onNext(b.build());
        responseObserver.onCompleted();
    }

    @Override
    public void rateRider(RateRiderRequest request, StreamObserver<RateRiderResponse> responseObserver) {
        String driverId = request.getDriverId();
        String tripId = request.getTripId();
        int rating = request.getRating();
        
        Query query = new Query(Criteria.where("_id").is(driverId)
                .and("rideHistory.tripId").is(tripId));
        Update update = new Update().set("rideHistory.$.riderRatingGiven", rating);
        
        long modifiedCount = mongoTemplate.updateFirst(query, update, Driver.class).getModifiedCount();
        
        RateRiderResponse response = RateRiderResponse.newBuilder()
                .setSuccess(modifiedCount > 0)
                .setMessage(modifiedCount > 0 ? "Rider rated successfully" : "Trip not found in history")
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


    @Override
    public void setRatingReceivedFromRider(SetRatingReceivedFromRiderRequest request, StreamObserver<SetRatingReceivedFromRiderResponse> responseObserver) {
        String driverId = request.getDriverId();
        String tripId = request.getTripId();
        int rating = request.getRating();

        Query query = new Query(Criteria.where("_id").is(driverId)
                .and("rideHistory.tripId").is(tripId));
        Update update = new Update().set("rideHistory.$.driverRatingReceived", rating);

        long modifiedCount = mongoTemplate.updateFirst(query, update, Driver.class).getModifiedCount();

        SetRatingReceivedFromRiderResponse response = SetRatingReceivedFromRiderResponse.newBuilder()
                .setSuccess(modifiedCount > 0)
                .setMessage(modifiedCount > 0 ? "Driver rating updated successfully" : "Trip not found in history")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private List<TripInfo> getActiveTripsForDriver(String driverId) {
        Driver driver = driverRepository.findById(driverId).orElse(null);
        if (driver != null && driver.getActiveTrips() != null) {
            return driver.getActiveTrips().stream().map(record -> TripInfo.newBuilder()
                    .setTripId(record.getTripId())
                    .setRiderId(record.getRiderId() != null ? record.getRiderId() : "")
                    .setRiderName(record.getRiderName() != null ? record.getRiderName() : "Unknown")
                    .setRiderRating(record.getRiderRating())
                    .setPickupStation(record.getPickupStation())
                    .setDestination(record.getDestination())
                    .setStatus(record.getStatus())
                    .setPickupTimestamp(record.getPickupTimestamp())
                    .setFare(record.getFare())
                    .build()).toList();
        }
        return new ArrayList<>();
    }

    @Override
    public void monitorDriverDashboard(MonitorDriverDashboardRequest request,
                                       StreamObserver<MonitorDriverDashboardResponse> responseObserver) {
        String driverId = request.getDriverId();
        String channel = "driver-dashboard:" + driverId;
        System.out.println("DEBUG: Driver " + driverId + " subscribed to dashboard updates on channel: " + channel);
        
        io.grpc.stub.ServerCallStreamObserver<MonitorDriverDashboardResponse> serverObserver = 
            (io.grpc.stub.ServerCallStreamObserver<MonitorDriverDashboardResponse>) responseObserver;

        // Send initial state (Active Trips)
        List<TripInfo> activeTrips = getActiveTripsForDriver(driverId);
        if (!activeTrips.isEmpty()) {
            MonitorDriverDashboardResponse initialResponse = MonitorDriverDashboardResponse.newBuilder()
                .addAllActiveTrips(activeTrips)
                .build();
            responseObserver.onNext(initialResponse);
        }

        // Create a thread pool for processing messages to avoid blocking the Redis listener thread
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();

        org.springframework.data.redis.connection.MessageListener listener = (message, pattern) -> {
            executor.submit(() -> {
                try {
                    String body = new String(message.getBody());
                    System.out.println("DEBUG: Received Redis message for driver " + driverId + ": " + body);
                    String[] parts = body.split(",", 2);
                    
                    if (parts.length >= 2) {
                        String type = parts[0];
                        String data = parts[1];
                        System.out.println("DEBUG: Message Type: " + type + ", Data: " + data);
                        
                        MonitorDriverDashboardResponse.Builder responseBuilder = MonitorDriverDashboardResponse.newBuilder();
                        
                        if ("MATCH_REQUEST".equals(type)) {
                            // Expected format: matchId::riderId::pickup::fare::dest
                            String[] matchParts = data.split("::");
                            if (matchParts.length >= 5) {
                                System.out.println("DEBUG: Processing MATCH_REQUEST for driver " + driverId);
                                responseBuilder.setMatchRequest(
                                    MatchRequest.newBuilder()
                                        .setMatchId(matchParts[0])
                                        .setRiderId(matchParts[1])
                                        .setPickupStation(matchParts[2])
                                        .setDestination(matchParts[4])
                                        .setFare(Integer.parseInt(matchParts[3].trim()))
                                        .build()
                                );
                            } else {
                                 System.err.println("DEBUG: Invalid MATCH_REQUEST format: " + data);
                            }
                        } else if ("TRIP_UPDATE".equals(type)) {
                            String[] tripParts = data.split(",");
                            if (tripParts.length >= 2) {
                                System.out.println("DEBUG: Processing TRIP_UPDATE for driver " + driverId);
                                responseBuilder.setTripUpdate(
                                    TripUpdate.newBuilder()
                                        .setTripId(tripParts[0])
                                        .setStatus(tripParts[1])
                                        .build()
                                );
                                // Include updated active trips list
                                responseBuilder.addAllActiveTrips(getActiveTripsForDriver(driverId));
                                
                                // Include available seats, earnings, and latest history in message field as JSON
                                Driver driver = driverRepository.findById(driverId).orElse(null);
                                if (driver != null) {
                                    StringBuilder jsonBuilder = new StringBuilder();
                                    jsonBuilder.append("{");
                                    jsonBuilder.append("\"availableSeats\": ").append(driver.getAvailableSeats());
                                    
                                    // Add Total Earnings
                                    int totalEarnings = driver.getTotalEarnings();
                                    if (totalEarnings == 0 && driver.getRideHistory() != null) {
                                        totalEarnings = driver.getRideHistory().stream().mapToInt(Driver.TripRecord::getFare).sum();
                                    }
                                    jsonBuilder.append(", \"totalEarnings\": ").append(totalEarnings);

                                    // Add Latest History Item if completed
                                    if ("COMPLETED".equalsIgnoreCase(tripParts[1]) && driver.getRideHistory() != null && !driver.getRideHistory().isEmpty()) {
                                        // Assuming the last item is the most recent one we just moved
                                        Driver.TripRecord lastTrip = driver.getRideHistory().get(driver.getRideHistory().size() - 1);
                                        jsonBuilder.append(", \"latestTrip\": {");
                                        jsonBuilder.append("\"id\": \"").append(lastTrip.getTripId()).append("\",");
                                        jsonBuilder.append("\"riderName\": \"").append(lastTrip.getRiderName()).append("\",");
                                        jsonBuilder.append("\"destination\": \"").append(lastTrip.getDestination()).append("\",");
                                        jsonBuilder.append("\"fare\": ").append(lastTrip.getFare()).append(",");
                                        jsonBuilder.append("\"date\": \"").append(LocalDate.now().toString()).append("\"");
                                        jsonBuilder.append("}");
                                    }
                                    
                                    jsonBuilder.append("}");
                                    responseBuilder.setMessage(jsonBuilder.toString());
                                }
                            }
                        }
                        
                        synchronized (responseObserver) {
                            responseObserver.onNext(responseBuilder.build());
                        }
                    } else {
                        System.err.println("DEBUG: Invalid message format received: " + body);
                    }
                } catch (Exception e) {
                    System.err.println("DEBUG: Error processing Redis message: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        };

        redisMessageListenerContainer.addMessageListener(listener, new org.springframework.data.redis.listener.ChannelTopic(channel));

        serverObserver.setOnCancelHandler(() -> {
            System.out.println("DEBUG: Driver " + driverId + " disconnected from dashboard stream");
            redisMessageListenerContainer.removeMessageListener(listener);
        });
    }
}

