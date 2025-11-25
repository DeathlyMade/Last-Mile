package com.lastmile.matching.grpc;

import com.lastmile.matching.model.Match;
import com.lastmile.matching.proto.*;
import com.lastmile.matching.repository.MatchRepository;
import com.lastmile.driver.proto.*;
import com.lastmile.station.proto.*;
import com.lastmile.trip.proto.*;
import com.lastmile.notification.proto.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

@GrpcService
public class MatchingGrpcService extends MatchingServiceGrpc.MatchingServiceImplBase {
    
    @Autowired
    private MatchRepository matchRepository;

    @GrpcClient("driver-service")
    private DriverServiceGrpc.DriverServiceBlockingStub driverStub;
    
    @GrpcClient("trip-service")
    private TripServiceGrpc.TripServiceBlockingStub tripStub;

    @GrpcClient("station-service")
    private StationServiceGrpc.StationServiceBlockingStub stationStub;

    @GrpcClient("notification-service")
    private NotificationServiceGrpc.NotificationServiceBlockingStub notificationStub;
    
    @Override
    public void matchRiderWithDriver(MatchRiderWithDriverRequest request,
                                    StreamObserver<MatchRiderWithDriverResponse> responseObserver) {
        String riderId = request.getRiderId();
        String metroStation = request.getMetroStation();
        String destination = request.getDestination();
        
        MatchRiderWithDriverResponse.Builder responseBuilder = MatchRiderWithDriverResponse.newBuilder();
        
        try {
            com.lastmile.driver.proto.DriverInfo matchedDriver = findDriver(metroStation, destination, null);
            
            if (matchedDriver == null) {
                responseBuilder.setSuccess(false)
                        .setMessage("No matching driver found");
            } else {
                String matchId = UUID.randomUUID().toString();
                int fare = calculateFare(metroStation, matchedDriver);
                
                Match match = new Match();
                match.setMatchId(matchId);
                match.setDriverId(matchedDriver.getDriverId());
                match.setRiderId(riderId);
                match.setPickupStation(metroStation);
                match.setDestination(destination);
                match.setStatus("MATCHED");
                match.setFare(fare);
                match.setTimestamp(System.currentTimeMillis());
                matchRepository.save(match);

                notifyDriver(matchedDriver.getDriverId(), riderId, matchId);
                
                responseBuilder.setMatchId(matchId)
                        .setDriverId(matchedDriver.getDriverId())
                        .setSuccess(true)
                        .setMessage("Match found, waiting for driver confirmation");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false)
                    .setMessage("Error matching: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void acceptMatch(AcceptMatchRequest request, StreamObserver<AcceptMatchResponse> responseObserver) {
        String matchId = request.getMatchId();
        String driverId = request.getDriverId();
        
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
                            .setFare((int)match.getFare())
                            .build();
                    
                    CreateTripResponse tripResponse = tripStub.createTrip(tripRequest);
                    
                    if (tripResponse.getSuccess()) {
                        match.setStatus("CONFIRMED");
                        match.setTripId(tripResponse.getTripId());
                        matchRepository.save(match);
                        
                        responseBuilder.setSuccess(true)
                                .setMessage("Match accepted and trip created")
                                .setTripId(tripResponse.getTripId());
                    } else {
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
            responseBuilder.setSuccess(false).setMessage("Error accepting match: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void declineMatch(DeclineMatchRequest request, StreamObserver<DeclineMatchResponse> responseObserver) {
        String matchId = request.getMatchId();
        String driverId = request.getDriverId();
        
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
                        match.setFare(calculateFare(match.getPickupStation(), newDriver));
                        match.setStatus("MATCHED"); // Reset status to MATCHED
                        match.setTimestamp(System.currentTimeMillis()); // Update timestamp
                        matchRepository.save(match);
                        
                        notifyDriver(newDriver.getDriverId(), match.getRiderId(), matchId);
                        
                        responseBuilder.setSuccess(true).setMessage("Match declined, reassigned to new driver");
                    } else {
                        // No new driver found, cancel match
                        match.setStatus("CANCELLED");
                        matchRepository.save(match);
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
                    .setTripId(match.getTripId() != null ? match.getTripId() : "")
                    .setSuccess(true);
        } else {
            responseBuilder.setSuccess(false);
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
        try {
            ListDriversRequest listRequest = ListDriversRequest.newBuilder()
                .setStation(pickupStation)
                .build();
                
            com.lastmile.driver.proto.ListDriversResponse listResponse = driverStub.listDrivers(listRequest);
            
            if (listResponse.getSuccess()) {
                for (com.lastmile.driver.proto.DriverInfo driver : listResponse.getDriversList()) {
                    if (driver.getDestination().equalsIgnoreCase(destination) &&
                        driver.getAvailableSeats() > 0 &&
                        driver.getMetroStationsList().contains(pickupStation)) {
                        
                        if (excludeDriverId == null || !driver.getDriverId().equals(excludeDriverId)) {
                            return driver;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error finding driver: " + e.getMessage());
        }
        return null;
    }

    private int calculateFare(String pickupStation, com.lastmile.driver.proto.DriverInfo driver) {
        int fare = 50;
        try {
            GetStationInfoResponse stationInfo = stationStub.getStationInfo(
                GetStationInfoRequest.newBuilder().setStationId(pickupStation).build()
            );
            
            if (stationInfo.getSuccess() && driver.hasCurrentLocation()) {
                double stationLat = stationInfo.getStation().getLatitude();
                double stationLon = stationInfo.getStation().getLongitude();
                double driverLat = driver.getCurrentLocation().getLatitude();
                double driverLon = driver.getCurrentLocation().getLongitude();
                
                double rawFare = Math.abs(driverLat - stationLat) + Math.abs(driverLon - stationLon);
                fare = (int) (rawFare * 10000);
                if (fare < 10) fare = 10; 
            }
        } catch (Exception e) {
            System.err.println("Error calculating fare: " + e.getMessage());
        }
        return fare;
    }

    private void notifyDriver(String driverId, String riderId, String matchId) {
        try {
            notificationStub.sendMatchNotification(
                SendMatchNotificationRequest.newBuilder()
                    .setDriverId(driverId)
                    .setRiderId(riderId)
                    .setMatchId(matchId)
                    .setTripId("") 
                    .build()
            );
        } catch (Exception e) {
            System.err.println("Failed to notify driver: " + e.getMessage());
        }
    }
}

