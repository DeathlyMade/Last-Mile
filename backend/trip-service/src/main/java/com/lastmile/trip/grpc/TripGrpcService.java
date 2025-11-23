package com.lastmile.trip.grpc;

import com.lastmile.trip.model.Trip;
import com.lastmile.trip.proto.*;
import com.lastmile.trip.repository.TripRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

@GrpcService
public class TripGrpcService extends TripServiceGrpc.TripServiceImplBase {
    
    @Autowired
    private TripRepository tripRepository;
    
    @Override
    public void createTrip(CreateTripRequest request,
                          StreamObserver<CreateTripResponse> responseObserver) {
        String driverId = request.getDriverId();
        String riderId = request.getRiderId();
        String pickupStation = request.getPickupStation();
        String destination = request.getDestination();
        String matchId = request.getMatchId();
        
        CreateTripResponse.Builder responseBuilder = CreateTripResponse.newBuilder();
        
        try {
            Trip trip = new Trip();
            trip.setTripId(UUID.randomUUID().toString());
            trip.setDriverId(driverId);
            trip.setRiderId(riderId);
            trip.setPickupStation(pickupStation);
            trip.setDestination(destination);
            trip.setStatus(Trip.TripStatus.SCHEDULED);
            trip.setCreatedAt(System.currentTimeMillis());
            trip.setMatchId(matchId);
            
            trip = tripRepository.save(trip);
            
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
    public void updateTripStatus(UpdateTripStatusRequest request,
                                 StreamObserver<UpdateTripStatusResponse> responseObserver) {
        String tripId = request.getTripId();
        com.lastmile.trip.proto.TripStatus status = request.getStatus();
        
        UpdateTripStatusResponse.Builder responseBuilder = UpdateTripStatusResponse.newBuilder();
        
        try {
            Trip trip = tripRepository.findById(tripId)
                    .orElseThrow(() -> new RuntimeException("Trip not found"));
            trip.setStatus(convertStatus(status));
            tripRepository.save(trip);
            
            responseBuilder.setSuccess(true)
                    .setMessage("Trip status updated successfully");
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
        
        RecordDropoffResponse.Builder responseBuilder = RecordDropoffResponse.newBuilder();
        
        try {
            Trip trip = tripRepository.findById(tripId)
                    .orElseThrow(() -> new RuntimeException("Trip not found"));
            trip.setDropoffTime(System.currentTimeMillis());
            trip.setStatus(Trip.TripStatus.COMPLETED);
            tripRepository.save(trip);
            
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
            case CANCELLED -> Trip.TripStatus.CANCELLED;
            default -> Trip.TripStatus.SCHEDULED;
        };
    }
    
    private com.lastmile.trip.proto.TripStatus convertStatusToProto(Trip.TripStatus status) {
        return switch (status) {
            case SCHEDULED -> com.lastmile.trip.proto.TripStatus.SCHEDULED;
            case ACTIVE -> com.lastmile.trip.proto.TripStatus.ACTIVE;
            case COMPLETED -> com.lastmile.trip.proto.TripStatus.COMPLETED;
            case CANCELLED -> com.lastmile.trip.proto.TripStatus.CANCELLED;
        };
    }
}

