package com.lastmile.matching.grpc;

import com.lastmile.matching.model.Match;
import com.lastmile.matching.proto.*;
import com.lastmile.matching.repository.MatchRepository;
import com.lastmile.driver.proto.*;
import com.lastmile.station.proto.*;
import com.lastmile.trip.proto.*;
import com.lastmile.notification.proto.*;
import com.lastmile.rider.proto.*;
import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

@GrpcService
public class MatchingGrpcService extends MatchingServiceGrpc.MatchingServiceImplBase {
    
    private static final Logger log = LoggerFactory.getLogger(MatchingGrpcService.class);
    
    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

    @GrpcClient("driver-service")
    private DriverServiceGrpc.DriverServiceBlockingStub driverStub;
    
    @GrpcClient("trip-service")
    private TripServiceGrpc.TripServiceBlockingStub tripStub;

    @GrpcClient("station-service")
    private StationServiceGrpc.StationServiceBlockingStub stationStub;

    @GrpcClient("notification-service")
    private NotificationServiceGrpc.NotificationServiceBlockingStub notificationStub;

    @GrpcClient("rider-service")
    private RiderServiceGrpc.RiderServiceBlockingStub riderStub;

    private <T extends AbstractStub<T>> T attachToken(T stub) {
        String token = AuthInterceptor.AUTH_TOKEN_KEY.get();
        if (token == null) return stub;
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return MetadataUtils.attachHeaders(stub, headers);
    }

    private <T extends AbstractStub<T>> T attachToken(T stub, String token) {
        if (token == null || token.isEmpty()) return attachToken(stub);
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return MetadataUtils.attachHeaders(stub, headers);
    }

    private void publishMatchUpdate(String riderId, String matchId, String status, String driverId, String tripId, int fare) {
        String channel = "match-status:" + riderId;
        String message = matchId + "," + status + "," + (driverId != null ? driverId : "") + "," + (tripId != null ? tripId : "") + "," + fare;
        redisTemplate.convertAndSend(channel, message);
    }

    private void publishDriverMatchRequest(String driverId, String matchId, String riderId, String pickup, String dest, int fare) {
        String channel = "driver-dashboard:" + driverId;
        String message = "MATCH_REQUEST," + matchId + "::" + riderId + "::" + pickup + "::" + fare + "::" + dest;
        log.info("Publishing MATCH_REQUEST to {}: {}", channel, message);
        redisTemplate.convertAndSend(channel, message);
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        redisMessageListenerContainer.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody());
            if (body.startsWith("DRIVER_AVAILABLE")) {
                String[] parts = body.split(",");
                if (parts.length > 1) {
                    String driverId = parts[1];
                    String token = parts.length > 2 ? parts[2] : null;
                    processPendingMatches(driverId, token);
                }
            }
        }, new org.springframework.data.redis.listener.ChannelTopic("driver-events"));
    }

    private void processPendingMatches(String driverId, String token) {
        System.out.println("DEBUG: Processing pending matches for new driver: " + driverId + " with token length: " + (token != null ? token.length() : "null"));
        List<Match> pendingMatches = matchRepository.findByStatus("PENDING");
        System.out.println("DEBUG: Found " + pendingMatches.size() + " pending matches total");
        if (pendingMatches.isEmpty()) return;

        try {
            // Fetch driver info
            System.out.println("DEBUG: Fetching driver info for " + driverId);
            com.lastmile.driver.proto.GetDriverInfoResponse driverInfo = attachToken(driverStub, token).getDriverInfo(
                com.lastmile.driver.proto.GetDriverInfoRequest.newBuilder().setDriverId(driverId).build()
            );

            if (!driverInfo.getSuccess()) {
                 System.out.println("DEBUG: Could not fetch info for driver " + driverId + ". Success=false");
                 return;
            }

            // Track available seats locally to prevent over-matching in this loop
            int currentSeats = driverInfo.getAvailableSeats();
            System.out.println("DEBUG: Driver " + driverId + " has " + currentSeats + " seats available.");

            for (Match match : pendingMatches) {
                if (currentSeats <= 0) {
                    System.out.println("DEBUG: No more seats available for driver " + driverId + ". Stopping match loop.");
                    break;
                }

                String pickup = match.getPickupStation();
                String dest = match.getDestination();
                System.out.println("DEBUG: Checking match " + match.getMatchId() + " (Rider: " + match.getRiderId() + ")");
                
                // Check if this driver matches
                boolean stationMatch = driverInfo.getMetroStationsList().contains(pickup);
                
                String driverDest = driverInfo.getDestination().toLowerCase();
                String matchDest = dest.toLowerCase();
                boolean destMatch = driverDest.contains(matchDest) || matchDest.contains(driverDest);
                
                System.out.println("DEBUG: StationMatch: " + stationMatch + " (Driver Stations: " + driverInfo.getMetroStationsList() + ", Pickup: " + pickup + ")");
                System.out.println("DEBUG: DestMatch: " + destMatch + " (Driver Dest: " + driverDest + ", Rider Dest: " + matchDest + ")");

                if (stationMatch && destMatch) {
                    System.out.println("DEBUG: Found match for pending request " + match.getMatchId() + " with driver " + driverId);
                    
                    var driverInfoBuilder = com.lastmile.driver.proto.DriverInfo.newBuilder()
                        .setDriverId(driverInfo.getDriverId());
                    
                    if (driverInfo.hasCurrentLocation()) {
                        driverInfoBuilder.setCurrentLocation(driverInfo.getCurrentLocation());
                    }

                    int fare = calculateFare(pickup, driverInfoBuilder.build(), token);

                    match.setDriverId(driverId);
                    match.setFare(fare);
                    match.setStatus("MATCHED"); // This effectively reserves the seat
                    match.setTimestamp(System.currentTimeMillis());
                    matchRepository.save(match);
                    
                    // Decrement local seat counter
                    currentSeats--;

                    notifyDriver(driverId, match.getRiderId(), match.getMatchId(), token);
                    publishMatchUpdate(match.getRiderId(), match.getMatchId(), "MATCHED", driverId, null, fare);
                    publishDriverMatchRequest(driverId, match.getMatchId(), match.getRiderId(), pickup, dest, fare);
                }
            }

        } catch (Exception e) {
            System.err.println("DEBUG: Error processing pending matches: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @Override
    public void matchRiderWithDriver(MatchRiderWithDriverRequest request,
                                    StreamObserver<MatchRiderWithDriverResponse> responseObserver) {
        String riderId = request.getRiderId();
        String metroStation = request.getMetroStation();
        String destination = request.getDestination();
        String rideRequestId = request.getRideRequestId();
        
        log.info("Matching request - riderId: {}, from: {}, to: {}", riderId, metroStation, destination);
        
        MatchRiderWithDriverResponse.Builder responseBuilder = MatchRiderWithDriverResponse.newBuilder();
        
        try {
            com.lastmile.driver.proto.DriverInfo matchedDriver = findDriver(metroStation, destination, null);
            
            if (matchedDriver == null) {
                // Save as PENDING
                Match match = new Match();
                match.setMatchId(rideRequestId);
                match.setRiderId(riderId);
                match.setPickupStation(metroStation);
                match.setDestination(destination);
                match.setStatus("PENDING");
                match.setTimestamp(System.currentTimeMillis());
                matchRepository.save(match);
                System.out.println("DEBUG: Match saved as PENDING: " + match);
                log.info("No driver available - match queued as PENDING: riderId: {}, matchId: {}", riderId, rideRequestId);
                responseBuilder.setMatchId(rideRequestId)
                        .setSuccess(true)
                        .setMessage("Request queued, waiting for driver");
            } else {
                String matchId = rideRequestId;
                if (matchId == null || matchId.isEmpty()) {
                    responseBuilder.setSuccess(false)
                            .setMessage("ride_request_id is required");
                    responseObserver.onNext(responseBuilder.build());
                    responseObserver.onCompleted();
                    return;
                }
                int fare = calculateFare(metroStation, matchedDriver, null);
                
                Match match = new Match();
                match.setMatchId(matchId);
                match.setDriverId(matchedDriver.getDriverId());
                match.setRiderId(riderId);
                match.setPickupStation(metroStation);
                match.setFare(fare);
                match.setDestination(destination);
                match.setStatus("MATCHED");
                match.setTimestamp(System.currentTimeMillis());
                matchRepository.save(match);

                notifyDriver(matchedDriver.getDriverId(), riderId, matchId, null);
                publishMatchUpdate(riderId, matchId, "MATCHED", matchedDriver.getDriverId(), null, fare);
                publishDriverMatchRequest(matchedDriver.getDriverId(), matchId, riderId, metroStation, destination, fare);
                
                log.info("Match found - riderId: {}, driverId: {}, matchId: {}, fare: {}", 
                    riderId, matchedDriver.getDriverId(), matchId, fare);
                
                responseBuilder.setMatchId(matchId)
                        .setDriverId(matchedDriver.getDriverId())
                        .setSuccess(true)
                        .setMessage("Match found, waiting for driver confirmation");
            }
        } catch (Exception e) {
            log.error("Error during matching - riderId: {}", riderId, e);
            responseBuilder.setSuccess(false)
                    .setMessage("Error matching: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void monitorMatchStatus(MonitorMatchStatusRequest request,
                                   StreamObserver<MonitorMatchStatusResponse> responseObserver) {
        String riderId = request.getRiderId();
        String channel = "match-status:" + riderId;
        
        io.grpc.stub.ServerCallStreamObserver<MonitorMatchStatusResponse> serverObserver = 
            (io.grpc.stub.ServerCallStreamObserver<MonitorMatchStatusResponse>) responseObserver;

        org.springframework.data.redis.connection.MessageListener listener = (message, pattern) -> {
            String body = new String(message.getBody());
            String[] parts = body.split(",");
            if (parts.length >= 2) {
                try {
                    String matchId = parts[0];
                    String statusStr = parts[1];
                    String driverId = parts.length > 2 ? parts[2] : "";
                    String tripId = parts.length > 3 ? parts[3] : "";
                    int fare = 0;
                    if (parts.length > 4) {
                        try { fare = Integer.parseInt(parts[4]); } catch (Exception e) {}
                    }
                    
                    MatchStatus status = MatchStatus.PENDING;
                    try { status = MatchStatus.valueOf(statusStr); } catch (Exception e) {}

                    MonitorMatchStatusResponse response = MonitorMatchStatusResponse.newBuilder()
                            .setMatchId(matchId)
                            .setStatus(status)
                            .setDriverId(driverId)
                            .setTripId(tripId)
                            .setFare(fare)
                            .setSuccess(true)
                            .build();
                            
                    synchronized (responseObserver) {
                        responseObserver.onNext(response);
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
    public void acceptMatch(AcceptMatchRequest request, StreamObserver<AcceptMatchResponse> responseObserver) {
        String matchId = request.getMatchId();
        String driverId = request.getDriverId();
        
        log.info("Driver accepting match - driverId: {}, matchId: {}", driverId, matchId);
        
        AcceptMatchResponse.Builder responseBuilder = AcceptMatchResponse.newBuilder();
        
        try {
            Optional<Match> matchOpt = matchRepository.findById(matchId);
            if (matchOpt.isPresent()) {
                Match match = matchOpt.get();
                if (match.getStatus().equals("MATCHED") && match.getDriverId().equals(driverId)) {
                    
                    CreateTripRequest tripRequest = CreateTripRequest.newBuilder()
                            .setDriverId(match.getDriverId())
                            .setRiderId(match.getRiderId())
                            .setPickupStation(match.getPickupStation())
                            .setDestination(match.getDestination())
                            .setMatchId(matchId)
                            .setFare(match.getFare())
                            .build();
                    
                    CreateTripResponse tripResponse = attachToken(tripStub).createTrip(tripRequest);
                    
                    if (tripResponse.getSuccess()) {
                        match.setStatus("CONFIRMED");
                        matchRepository.save(match);
                        publishMatchUpdate(match.getRiderId(), matchId, "CONFIRMED", match.getDriverId(), tripResponse.getTripId(), match.getFare());
                        
                        log.info("Match accepted and trip created - driverId: {}, riderId: {}, tripId: {}", 
                            driverId, match.getRiderId(), tripResponse.getTripId());
                        
                        responseBuilder.setSuccess(true)
                                .setMessage("Match accepted and trip created");
                    } else {
                        log.warn("Trip creation failed for match - matchId: {}, driverId: {}", matchId, driverId);
                        responseBuilder.setSuccess(false)
                                .setMessage("Failed to create trip: " + tripResponse.getMessage());
                    }
                } else {
                     responseBuilder.setSuccess(false).setMessage("Match not valid for acceptance");
                }
            } else {
                responseBuilder.setSuccess(false).setMessage("Match not found");
            }
        } catch (Exception e) {
            log.error("Error accepting match - matchId: {}, driverId: {}", matchId, driverId, e);
            responseBuilder.setSuccess(false).setMessage("Error accepting match: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void declineMatch(DeclineMatchRequest request, StreamObserver<DeclineMatchResponse> responseObserver) {
        String matchId = request.getMatchId();
        String driverId = request.getDriverId();
        
        log.info("Driver declining match - driverId: {}, matchId: {}", driverId, matchId);
        
        DeclineMatchResponse.Builder responseBuilder = DeclineMatchResponse.newBuilder();
        
        try {
            Optional<Match> matchOpt = matchRepository.findById(matchId);
            if (matchOpt.isPresent()) {
                Match match = matchOpt.get();
                if (match.getStatus().equals("MATCHED") && match.getDriverId().equals(driverId)) {
                    
                    // Try to find a new driver
                    com.lastmile.driver.proto.DriverInfo newDriver = findDriver(match.getPickupStation(), match.getDestination(), driverId);
                    
                    if (newDriver != null) {
                        // Update existing match with new driver
                        match.setDriverId(newDriver.getDriverId());
                        match.setFare(calculateFare(match.getPickupStation(), newDriver, null));
                        match.setStatus("MATCHED"); // Reset status to MATCHED
                        match.setTimestamp(System.currentTimeMillis()); // Update timestamp
                        matchRepository.save(match);
                        
                        notifyDriver(newDriver.getDriverId(), match.getRiderId(), matchId, null);
                        
                        log.info("Match declined and reassigned - oldDriver: {}, newDriver: {}, matchId: {}", 
                            driverId, newDriver.getDriverId(), matchId);
                        
                        responseBuilder.setSuccess(true).setMessage("Match declined, reassigned to new driver");
                    } else {
                        // No new driver found, cancel match
                        match.setStatus("PENDING");
                        matchRepository.save(match);
                        log.warn("Match declined, no replacement driver found - matchId: {}, driverId: {}", matchId, driverId);
                        responseBuilder.setSuccess(true).setMessage("Match declined, no new driver found");
                    }
                } else {
                    responseBuilder.setSuccess(false).setMessage("Match not valid for decline");
                }
            } else {
                responseBuilder.setSuccess(false).setMessage("Match not found");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false).setMessage("Error declining match: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getMatchStatus(GetMatchStatusRequest request,
                              StreamObserver<GetMatchStatusResponse> responseObserver) {
        String matchId = request.getMatchId();
        
        GetMatchStatusResponse.Builder responseBuilder = GetMatchStatusResponse.newBuilder();
        
        Optional<Match> matchOpt = matchRepository.findById(matchId);
        
        if (matchOpt.isPresent()) {
            Match match = matchOpt.get();
            responseBuilder.setMatchId(match.getMatchId())
                    .setDriverId(match.getDriverId())
                    .setRiderId(match.getRiderId())
                    .setStatus(convertStatus(match.getStatus()))
                    .setSuccess(true);
        } else {
            responseBuilder.setSuccess(false);
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void cancelMatchRequestByRider(CancelMatchRequest request,
                                         StreamObserver<CancelMatchResponse> responseObserver) {
        String matchId = request.getMatchId();
        String riderId = request.getRiderId();
        System.out.println("DEBUG: Received CancelMatchRequestByRider for matchId: " + matchId + ", riderId: " + riderId);
        long startTime = System.currentTimeMillis();
        
        CancelMatchResponse.Builder responseBuilder = CancelMatchResponse.newBuilder();
        
        try {
            Optional<Match> matchOpt = matchRepository.findById(matchId);
            if (matchOpt.isPresent()) {
                Match match = matchOpt.get();
                match.setStatus("CANCELLED");
                matchRepository.save(match);
                publishMatchUpdate(riderId, matchId, "CANCELLED", null, null, 0);
                responseBuilder.setSuccess(true)
                        .setMessage("Match cancelled successfully"); 

                // call CancelRideRequest in rider service
                try {
                    attachToken(riderStub).cancelRideRequest(
                        CancelRideRequestRequest.newBuilder()
                            .setRiderId(riderId)
                            .build()
                    );
                    System.out.println("DEBUG: Called riderStub.cancelRideRequest");
                } catch (Exception e) {
                    System.err.println("DEBUG: Failed to cancel ride request in RiderService: " + e.getMessage());
                }
                
            } 
            else {
                responseBuilder.setSuccess(false)
                        .setMessage("Match not found");
            }
            
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("DEBUG: CancelMatchRequestByRider completed in " + duration + "ms");

        } catch (Exception e) {
            System.err.println("DEBUG: Error cancelling match: " + e.getMessage());
            responseBuilder.setSuccess(false)
                    .setMessage("Error cancelling match: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    private com.lastmile.matching.proto.MatchStatus convertStatus(String status) {
        if (status == null) return com.lastmile.matching.proto.MatchStatus.PENDING;
        try {
            return com.lastmile.matching.proto.MatchStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return com.lastmile.matching.proto.MatchStatus.PENDING;
        }
    }

    private com.lastmile.driver.proto.DriverInfo findDriver(String pickupStation, String destination, String excludeDriverId) {
        System.out.println("DEBUG: Finding driver for station: " + pickupStation + ", destination: " + destination);
        try {
            ListDriversRequest listRequest = ListDriversRequest.newBuilder()
                .setStation(pickupStation)
                .build();
                
            com.lastmile.driver.proto.ListDriversResponse listResponse = attachToken(driverStub).listDrivers(listRequest);
            
            if (listResponse.getSuccess()) {
                System.out.println("DEBUG: Found " + listResponse.getDriversCount() + " drivers at station " + pickupStation);
                for (com.lastmile.driver.proto.DriverInfo driver : listResponse.getDriversList()) {
                    System.out.println("DEBUG: Checking driver: " + driver.getDriverId() + ", Dest: " + driver.getDestination() + ", Seats: " + driver.getAvailableSeats());
                    
                    String driverDest = driver.getDestination().toLowerCase();
                    String riderDest = destination.toLowerCase();
                    
                    // Relaxed matching: check if one contains the other
                    boolean destMatch = driverDest.equals(riderDest) || driverDest.contains(riderDest) || riderDest.contains(driverDest);
                    
                    if (destMatch && driver.getMetroStationsList().contains(pickupStation)) {
                        
                        // Check for PENDING matches for this driver to avoid double booking
                        long pendingMatches = matchRepository.countByDriverIdAndStatus(driver.getDriverId(), "MATCHED");
                        int effectiveSeats = driver.getAvailableSeats() - (int) pendingMatches;
                        
                        System.out.println("DEBUG: Driver " + driver.getDriverId() + " has " + pendingMatches + " pending matches. Effective seats: " + effectiveSeats);

                        if (effectiveSeats > 0) {
                            if (excludeDriverId == null || !driver.getDriverId().equals(excludeDriverId)) {
                                System.out.println("DEBUG: >> Match found: " + driver.getDriverId());
                                return driver;
                            }
                        } else {
                            System.out.println("DEBUG: >> Driver " + driver.getDriverId() + " skipped due to no effective seats.");
                        }
                    } else {
                         System.out.println("DEBUG: >> Driver " + driver.getDriverId() + " did not match. DestMatch: " + destMatch);
                    }
                }
            } else {
                System.out.println("DEBUG: ListDrivers failed: " + listResponse.getSuccess());
            }
        } catch (Exception e) {
            System.err.println("DEBUG: Error finding driver: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    private int calculateFare(String pickupStation, com.lastmile.driver.proto.DriverInfo driver, String token) {
        int fare = 50;
        try {
            GetStationInfoResponse stationInfo = attachToken(stationStub, token).getStationInfo(
                GetStationInfoRequest.newBuilder().setStationId(pickupStation).build()
            );
            
            if (stationInfo.getSuccess() && driver.hasCurrentLocation()) {
                double stationLat = stationInfo.getStation().getLatitude();
                double stationLon = stationInfo.getStation().getLongitude();
                double driverLat = driver.getCurrentLocation().getLatitude();
                double driverLon = driver.getCurrentLocation().getLongitude();
                System.out.println("DEBUG: Driver location: " + driverLat + ", " + driverLon);
                System.out.println("DEBUG: Station location: " + stationLat + ", " + stationLon);
                if (driverLat == 0.0 && driverLon == 0.0) {
                    System.out.println("DEBUG: Driver location is (0,0). Using default fallback fare.");
                    return 50; // Fallback default
                }

                double rawFare = Math.abs(driverLat - stationLat) + Math.abs(driverLon - stationLon);
                fare = (int) (rawFare * 100);
            }
        } catch (Exception e) {
            System.err.println("Error calculating fare: " + e.getMessage());
        }
        return fare;
    }

    private void notifyDriver(String driverId, String riderId, String matchId, String token) {
        System.out.println("DEBUG: Notifying driver: " + driverId + ", rider: " + riderId + ", match: " + matchId);
        try {
            attachToken(notificationStub, token).sendMatchNotification(
                SendMatchNotificationRequest.newBuilder()
                    .setDriverId(driverId)
                    .setRiderId(riderId)
                    .setMatchId(matchId)
                    .build()
            );
        } catch (Exception e) {
            System.err.println("Failed to notify driver: " + e.getMessage());
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 30000) // Check every 30s
    public void checkMatchTimeouts() {
        System.out.println("DEBUG: Running checkMatchTimeouts...");
        long now = System.currentTimeMillis();
        long timeoutThreshold = 45000; // 45 seconds timeout

        List<Match> matchedRides = matchRepository.findByStatus("MATCHED");
        for (Match match : matchedRides) {
            if (now - match.getTimestamp() > timeoutThreshold) {
                System.out.println("DEBUG: Match " + match.getMatchId() + " timed out. Reverting to PENDING. Driver was: " + match.getDriverId());
                
                // Revert to PENDING
                match.setStatus("PENDING");
                match.setDriverId(null); // Clear driver so it can be picked up by anyone
                // Don't reset timestamp completely, or maybe reset it to now to give it fresh priority? 
                // Let's keep original timestamp or just update it to now so it doesn't look like it's been waiting forever if we sort by time.
                // Actually, for "PENDING" queue, older might be better. But here we just want it to be valid.
                
                matchRepository.save(match);
                
                // Notify Rider -> "Searching for new driver..."
                publishMatchUpdate(match.getRiderId(), match.getMatchId(), "PENDING", null, null, 0);
                
                // Ideally cancel the request sent to the driver dashboard too, but the dashboard usually just polls or listens.
                // We can send a CANCEL/TIMEOUT event to the driver if needed.
            }
        }
    }
}

