package com.lastmile.matching.grpc;

import com.lastmile.driver.proto.DriverServiceGrpc;
import com.lastmile.driver.proto.GetDriverInfoRequest;
import com.lastmile.driver.proto.ListDriversRequest;
import com.lastmile.matching.proto.*;
import com.lastmile.trip.proto.CreateTripRequest;
import com.lastmile.trip.proto.CreateTripResponse;
import com.lastmile.trip.proto.TripServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.*;

@GrpcService
public class MatchingGrpcService extends MatchingServiceGrpc.MatchingServiceImplBase {
    
    @GrpcClient("driver-service")
    private DriverServiceGrpc.DriverServiceBlockingStub driverStub;
    
    @GrpcClient("trip-service")
    private TripServiceGrpc.TripServiceBlockingStub tripStub;
    
    private final Map<String, MatchInfo> matches = new HashMap<>();
    
    @Override
    public void matchRiderWithDriver(MatchRiderWithDriverRequest request,
                                    StreamObserver<MatchRiderWithDriverResponse> responseObserver) {
        String rideRequestId = request.getRideRequestId();
        String riderId = request.getRiderId();
        String metroStation = request.getMetroStation();
        String destination = request.getDestination();
        
        MatchRiderWithDriverResponse.Builder responseBuilder = MatchRiderWithDriverResponse.newBuilder();
        
        try {
            List<com.lastmile.driver.proto.GetDriverInfoResponse> allDrivers = getAllDrivers();
            
            com.lastmile.driver.proto.GetDriverInfoResponse matchedDriver = null;
            for (com.lastmile.driver.proto.GetDriverInfoResponse driver : allDrivers) {
                if (driver.getDestination().equals(destination) &&
                    driver.getMetroStationsList().contains(metroStation) &&
                    driver.getAvailableSeats() > 0) {
                    matchedDriver = driver;
                    break;
                }
            }
            
            if (matchedDriver == null) {
                responseBuilder.setSuccess(false)
                        .setMessage("No matching driver found");
            } else {
                String matchId = UUID.randomUUID().toString();
                
                CreateTripRequest tripRequest = CreateTripRequest.newBuilder()
                        .setDriverId(matchedDriver.getDriverId())
                        .setRiderId(riderId)
                        .setPickupStation(metroStation)
                        .setDestination(destination)
                        .setMatchId(matchId)
                        .build();
                
                CreateTripResponse tripResponse = tripStub.createTrip(tripRequest);
                
                if (!tripResponse.getSuccess()) {
                    responseBuilder.setSuccess(false)
                            .setMessage("Failed to create trip");
                } else {
                    MatchInfo matchInfo = new MatchInfo();
                    matchInfo.setMatchId(matchId);
                    matchInfo.setDriverId(matchedDriver.getDriverId());
                    matchInfo.setRiderId(riderId);
                    matchInfo.setTripId(tripResponse.getTripId());
                    matchInfo.setStatus(com.lastmile.matching.proto.MatchStatus.MATCHED);
                    matches.put(matchId, matchInfo);
                    
                    responseBuilder.setMatchId(matchId)
                            .setDriverId(matchedDriver.getDriverId())
                            .setSuccess(true)
                            .setMessage("Rider matched with driver successfully");
                }
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false)
                    .setMessage("Error matching: " + e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void getMatchStatus(GetMatchStatusRequest request,
                              StreamObserver<GetMatchStatusResponse> responseObserver) {
        String matchId = request.getMatchId();
        
        GetMatchStatusResponse.Builder responseBuilder = GetMatchStatusResponse.newBuilder();
        
        MatchInfo matchInfo = matches.get(matchId);
        if (matchInfo != null) {
            responseBuilder.setMatchId(matchInfo.getMatchId())
                    .setDriverId(matchInfo.getDriverId())
                    .setRiderId(matchInfo.getRiderId())
                    .setStatus(matchInfo.getStatus())
                    .setTripId(matchInfo.getTripId() != null ? matchInfo.getTripId() : "")
                    .setSuccess(true);
        } else {
            responseBuilder.setSuccess(false);
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    private List<com.lastmile.driver.proto.GetDriverInfoResponse> getAllDrivers() {
        List<com.lastmile.driver.proto.GetDriverInfoResponse> drivers = new ArrayList<>();
        
        try {
            com.lastmile.driver.proto.ListDriversResponse listResponse = 
                    driverStub.listDrivers(ListDriversRequest.getDefaultInstance());
            
            if (listResponse.getSuccess()) {
                for (com.lastmile.driver.proto.DriverInfo info : listResponse.getDriversList()) {
                    com.lastmile.driver.proto.GetDriverInfoResponse.Builder builder = 
                        com.lastmile.driver.proto.GetDriverInfoResponse.newBuilder()
                            .setDriverId(info.getDriverId())
                            .setDestination(info.getDestination())
                            .setAvailableSeats(info.getAvailableSeats())
                            .addAllMetroStations(info.getMetroStationsList())
                            .setRating(info.getRating())
                            .setSuccess(true);
                    
                    if (info.hasCurrentLocation()) {
                        builder.setCurrentLocation(info.getCurrentLocation());
                    }
                    
                    drivers.add(builder.build());
                }
            }
        } catch (Exception e) {
        }
        
        return drivers;
    }
    
    private static class MatchInfo {
        private String matchId;
        private String driverId;
        private String riderId;
        private String tripId;
        private com.lastmile.matching.proto.MatchStatus status;
        
        public String getMatchId() { return matchId; }
        public void setMatchId(String matchId) { this.matchId = matchId; }
        public String getDriverId() { return driverId; }
        public void setDriverId(String driverId) { this.driverId = driverId; }
        public String getRiderId() { return riderId; }
        public void setRiderId(String riderId) { this.riderId = riderId; }
        public String getTripId() { return tripId; }
        public void setTripId(String tripId) { this.tripId = tripId; }
        public com.lastmile.matching.proto.MatchStatus getStatus() { return status; }
        public void setStatus(com.lastmile.matching.proto.MatchStatus status) { this.status = status; }
    }
}

