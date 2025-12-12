package com.lastmile.trip.grpc;

import com.lastmile.trip.model.Trip;
import com.lastmile.trip.proto.*;
import com.lastmile.trip.repository.TripRepository;
import com.lastmile.driver.proto.*;
import com.lastmile.rider.proto.*;
import com.lastmile.user.proto.*;
import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

@GrpcService
public class TripGrpcService extends TripServiceGrpc.TripServiceImplBase {
    
    private static final Logger log = LoggerFactory.getLogger(TripGrpcService.class);
    
    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate;

    @Autowired
    private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

    @GrpcClient("rider-service")
    private RiderServiceGrpc.RiderServiceBlockingStub riderStub;

    @GrpcClient("driver-service")
    private DriverServiceGrpc.DriverServiceBlockingStub driverStub;

    @GrpcClient("user-service")
    private UserServiceGrpc.UserServiceBlockingStub userStub;

    private <T extends AbstractStub<T>> T attachToken(T stub) {
        String token = AuthInterceptor.AUTH_TOKEN_KEY.get();
        if (token == null) return stub;
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return MetadataUtils.attachHeaders(stub, headers);
    }

    private void publishTripUpdate(String tripId, String status, String driverId, String riderId) {
        // Publish to trip channel
        String tripChannel = "trip-updates:" + tripId;
        String message = tripId + "," + status;
        redisTemplate.convertAndSend(tripChannel, message);

        // Publish to driver dashboard
        if (driverId != null) {
            String driverChannel = "driver-dashboard:" + driverId;
            String driverMessage = "TRIP_UPDATE," + tripId + "," + status;
            redisTemplate.convertAndSend(driverChannel, driverMessage);
        }
    }
    
    @Override
    public void createTrip(CreateTripRequest request,
                          StreamObserver<CreateTripResponse> responseObserver) {
        String driverId = request.getDriverId();
        String riderId = request.getRiderId();
        String pickupStation = request.getPickupStation();
        String destination = request.getDestination();
        String matchId = request.getMatchId();
        int fare = request.getFare();
        
        log.info("Creating trip - driverId: {}, riderId: {}, pickup: {}, dest: {}", 
            driverId, riderId, pickupStation, destination);
        
        CreateTripResponse.Builder responseBuilder = CreateTripResponse.newBuilder();
        
        try {
            Trip trip = new Trip();
            String tripId = matchId;
            if (tripId == null || tripId.isEmpty()) {
                responseBuilder.setSuccess(false)
                        .setMessage("match_id is required");
                responseObserver.onNext(responseBuilder.build());
                responseObserver.onCompleted();
                return;
            }
            trip.setTripId(tripId);
            trip.setDriverId(driverId);
            trip.setRiderId(riderId);
            trip.setPickupStation(pickupStation);
            trip.setDestination(destination);
            trip.setStatus(Trip.TripStatus.SCHEDULED);
            trip.setCreatedAt(System.currentTimeMillis());
            trip.setFare(fare);
            
            trip = tripRepository.save(trip);



            // Fetch Rider Info for Rating
            double riderRating = 0.0;
            try {
                GetRiderInfoResponse riderInfo = attachToken(riderStub).getRiderInfo(
                    GetRiderInfoRequest.newBuilder().setRiderId(riderId).build()
                );
                if (riderInfo.getSuccess()) {
                    riderRating = riderInfo.getRating();
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch rider info: " + e.getMessage());
            }

            // Fetch Rider Name from User Service
            String riderName = "Rider " + riderId;
            try {
                GetUserProfileResponse userProfile = attachToken(userStub).getUserProfile(
                    GetUserProfileRequest.newBuilder().setUserId(riderId).build()
                );
                if (userProfile.getSuccess()) {
                    riderName = userProfile.getName();
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch rider name: " + e.getMessage());
            }

            // Notify Driver
            try {
                AcceptTripResponse driverResponse = attachToken(driverStub).acceptTrip(AcceptTripRequest.newBuilder()
                    .setDriverId(driverId)
                    .setTripId(tripId)
                    .setRiderId(riderId)
                    .setRiderName(riderName)
                    .setRiderRating(riderRating)
                    .setPickupStation(pickupStation)
                    .setDestination(destination)
                    .setFare(fare)
                    .build());
            } catch (Exception e) {
                System.err.println("Failed to notify driver: " + e.getMessage());
            }

            publishTripUpdate(trip.getTripId(), "SCHEDULED", driverId, riderId);

            String driverName = "Driver " + driverId;
            try {
                GetUserProfileResponse userProfile = attachToken(userStub).getUserProfile(
                    GetUserProfileRequest.newBuilder().setUserId(driverId).build()
                );
                if (userProfile.getSuccess()) {
                    driverName = userProfile.getName();
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch driver name: " + e.getMessage());
            }
            // Notify Rider
            try {
                attachToken(riderStub).matchedWithDriver(MatchedWithDriverRequest.newBuilder()
                    .setRiderId(riderId)
                    .setDriverId(driverId)
                    .setTripId(tripId)
                    .setDriverName(driverName)
                    .build());
            } catch (Exception e) {
                System.err.println("Failed to notify rider: " + e.getMessage());
            }
            System.out.println("Trip created successfully");    
            log.info("Trip created successfully - tripId: {}, driverId: {}, riderId: {}", 
                trip.getTripId(), driverId, riderId);
            responseBuilder.setTripId(trip.getTripId())
                    .setSuccess(true)
                    .setMessage("Trip created successfully");
        } catch (Exception e) {
            log.error("Failed to create trip - driverId: {}, riderId: {}", driverId, riderId, e);
            responseBuilder.setSuccess(false)
                    .setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    

    
    @Override
    public void getTripInfo(GetTripInfoRequest request,
                           StreamObserver<GetTripInfoResponse> responseObserver) {
        String tripId = request.getTripId();
        
        GetTripInfoResponse.Builder responseBuilder = GetTripInfoResponse.newBuilder();
        
        try {
            Trip trip = tripRepository.findById(tripId)
                    .orElseThrow(() -> new RuntimeException("Trip not found"));
            
            responseBuilder.setTripId(trip.getTripId())
                    .setDriverId(trip.getDriverId())
                    .setRiderId(trip.getRiderId())
                    .setPickupStation(trip.getPickupStation())
                    .setDestination(trip.getDestination())
                    .setStatus(convertStatusToProto(trip.getStatus()))
                    .setCreatedAt(trip.getCreatedAt())
                    .setPickupTime(trip.getPickupTime())
                    .setDropoffTime(trip.getDropoffTime())
                    .setFare(trip.getFare())
                    .setSuccess(true);
        } catch (Exception e) {
            responseBuilder.setSuccess(false);
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void recordPickup(RecordPickupRequest request,
                            StreamObserver<RecordPickupResponse> responseObserver) {
        String tripId = request.getTripId();
        
        RecordPickupResponse.Builder responseBuilder = RecordPickupResponse.newBuilder();
        
        try {
            Trip trip = tripRepository.findById(tripId)
                    .orElseThrow(() -> new RuntimeException("Trip not found"));
            trip.setPickupTime(System.currentTimeMillis());
            trip.setStatus(Trip.TripStatus.ACTIVE);
            tripRepository.save(trip);
            
            log.info("Pickup recorded - tripId: {}, driverId: {}, riderId: {}", 
                tripId, trip.getDriverId(), trip.getRiderId());



            // Notify Driver
            try {
                attachToken(driverStub).startTrip(StartTripRequest.newBuilder()
                    .setDriverId(trip.getDriverId())
                    .setTripId(tripId)
                    .build());
            } catch (Exception e) {
                System.err.println("Failed to start trip on driver service: " + e.getMessage());
            }

            publishTripUpdate(tripId, "ACTIVE", trip.getDriverId(), trip.getRiderId());

            // Notify Rider
            try {
                attachToken(riderStub).rideStarted(RideStartedRequest.newBuilder()
                    .setRiderId(trip.getRiderId())
                    .setTripId(tripId)
                    .build());
            } catch (Exception e) {
                System.err.println("Failed to notify rider of ride start: " + e.getMessage());
            }
            
            responseBuilder.setSuccess(true)
                    .setMessage("Pickup recorded successfully");
        } catch (Exception e) {
            responseBuilder.setSuccess(false)
                    .setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void recordDropoff(RecordDropoffRequest request,
                             StreamObserver<RecordDropoffResponse> responseObserver) {
        String tripId = request.getTripId();
        int fare = request.getFare();
        
        RecordDropoffResponse.Builder responseBuilder = RecordDropoffResponse.newBuilder();
        
        try {
            Trip trip = tripRepository.findById(tripId)
                    .orElseThrow(() -> new RuntimeException("Trip not found"));
            trip.setDropoffTime(System.currentTimeMillis());
            trip.setStatus(Trip.TripStatus.COMPLETED);
            if (fare > 0) {
                trip.setFare(fare);
            }
            tripRepository.save(trip);
            
            log.info("Dropoff recorded - tripId: {}, fare: {}, driverId: {}, riderId: {}", 
                tripId, trip.getFare(), trip.getDriverId(), trip.getRiderId());



            // Notify Driver
            try {
                attachToken(driverStub).completeActiveTrip(CompleteActiveTripRequest.newBuilder()
                    .setDriverId(trip.getDriverId())
                    .setTripId(tripId)
                    .build());
            } catch (Exception e) {
                System.err.println("Failed to complete trip on driver service: " + e.getMessage());
            }

            publishTripUpdate(tripId, "COMPLETED", trip.getDriverId(), trip.getRiderId());

            // Notify Rider
            try {
                attachToken(riderStub).rideCompleted(RideCompletedRequest.newBuilder()
                    .setRiderId(trip.getRiderId())
                    .setTripId(tripId)
                    .setDropoffTime(trip.getDropoffTime())
                    .setFare(trip.getFare())
                    .build());
            } catch (Exception e) {
                System.err.println("Failed to notify rider of ride completion: " + e.getMessage());
            }
            
            responseBuilder.setSuccess(true)
                    .setMessage("Dropoff recorded successfully");
        } catch (Exception e) {
            responseBuilder.setSuccess(false)
                    .setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void monitorTripUpdates(MonitorTripUpdatesRequest request,
                                   StreamObserver<MonitorTripUpdatesResponse> responseObserver) {
        String tripId = request.getTripId();
        String channel = "trip-updates:" + tripId;
        
        io.grpc.stub.ServerCallStreamObserver<MonitorTripUpdatesResponse> serverObserver = 
            (io.grpc.stub.ServerCallStreamObserver<MonitorTripUpdatesResponse>) responseObserver;

        org.springframework.data.redis.connection.MessageListener listener = (message, pattern) -> {
            String body = new String(message.getBody());
            String[] parts = body.split(",");
            if (parts.length >= 2) {
                try {
                    String tId = parts[0];
                    String statusStr = parts[1];
                    
                    TripStatus status = TripStatus.SCHEDULED;
                    try { status = TripStatus.valueOf(statusStr); } catch (Exception e) {}

                    MonitorTripUpdatesResponse response = MonitorTripUpdatesResponse.newBuilder()
                            .setTripId(tId)
                            .setStatus(status)
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
    
    private Trip.TripStatus convertStatus(com.lastmile.trip.proto.TripStatus status) {
        return switch (status) {
            case SCHEDULED -> Trip.TripStatus.SCHEDULED;
            case ACTIVE -> Trip.TripStatus.ACTIVE;
            case COMPLETED -> Trip.TripStatus.COMPLETED;
            default -> Trip.TripStatus.SCHEDULED;
        };
    }
    
    private com.lastmile.trip.proto.TripStatus convertStatusToProto(Trip.TripStatus status) {
        return switch (status) {
            case SCHEDULED -> com.lastmile.trip.proto.TripStatus.SCHEDULED;
            case ACTIVE -> com.lastmile.trip.proto.TripStatus.ACTIVE;
            case COMPLETED -> com.lastmile.trip.proto.TripStatus.COMPLETED;
        };
    }
}

