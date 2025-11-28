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
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

@GrpcService
public class TripGrpcService extends TripServiceGrpc.TripServiceImplBase {
    
    @Autowired
    private TripRepository tripRepository;

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
    
    @Override
    public void createTrip(CreateTripRequest request,
                          StreamObserver<CreateTripResponse> responseObserver) {
        String driverId = request.getDriverId();
        String riderId = request.getRiderId();
        String pickupStation = request.getPickupStation();
        String destination = request.getDestination();
        String matchId = request.getMatchId();
        int fare = request.getFare();
        
        CreateTripResponse.Builder responseBuilder = CreateTripResponse.newBuilder();
        
        try {
            Trip trip = new Trip();
            String tripId = UUID.randomUUID().toString();
            trip.setTripId(tripId);
            trip.setDriverId(driverId);
            trip.setRiderId(riderId);
            trip.setPickupStation(pickupStation);
            trip.setDestination(destination);
            trip.setStatus(Trip.TripStatus.SCHEDULED);
            trip.setCreatedAt(System.currentTimeMillis());
            trip.setMatchId(matchId);
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

            // Notify Rider
            try {
                attachToken(riderStub).matchedWithDriver(MatchedWithDriverRequest.newBuilder()
                    .setRiderId(riderId)
                    .setDriverId(driverId)
                    .setTripId(tripId)
                    .build());
            } catch (Exception e) {
                System.err.println("Failed to notify rider: " + e.getMessage());
            }
            
            responseBuilder.setTripId(trip.getTripId())
                    .setSuccess(true)
                    .setMessage("Trip created successfully");
        } catch (Exception e) {
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

            // Notify Driver
            try {
                driverStub.startTrip(StartTripRequest.newBuilder()
                    .setDriverId(trip.getDriverId())
                    .setTripId(tripId)
                    .build());
            } catch (Exception e) {
                System.err.println("Failed to start trip on driver service: " + e.getMessage());
            }

            // Notify Rider
            try {
                riderStub.rideStarted(RideStartedRequest.newBuilder()
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

            // Notify Driver
            try {
                driverStub.completeActiveTrip(CompleteActiveTripRequest.newBuilder()
                    .setDriverId(trip.getDriverId())
                    .setTripId(tripId)
                    .build());
            } catch (Exception e) {
                System.err.println("Failed to complete trip on driver service: " + e.getMessage());
            }

            // Notify Rider
            try {
                riderStub.rideCompleted(RideCompletedRequest.newBuilder()
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

