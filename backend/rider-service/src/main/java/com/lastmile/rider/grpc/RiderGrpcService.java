package com.lastmile.rider.grpc;

import com.lastmile.rider.model.RideRequest;
import com.lastmile.rider.proto.*;
import com.lastmile.rider.repository.RideRequestRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

@GrpcService
public class RiderGrpcService extends RiderServiceGrpc.RiderServiceImplBase {
    
    @Autowired
    private RideRequestRepository rideRequestRepository;
    
    @Override
    public void registerRideRequest(RegisterRideRequestRequest request,
                                   StreamObserver<RegisterRideRequestResponse> responseObserver) {
        String riderId = request.getRiderId();
        String metroStation = request.getMetroStation();
        String destination = request.getDestination();
        long arrivalTime = request.getArrivalTime();
        
        RegisterRideRequestResponse.Builder responseBuilder = RegisterRideRequestResponse.newBuilder();
        
        try {
            RideRequest rideRequest = new RideRequest();
            rideRequest.setRideRequestId(UUID.randomUUID().toString());
            rideRequest.setRiderId(riderId);
            rideRequest.setMetroStation(metroStation);
            rideRequest.setDestination(destination);
            rideRequest.setArrivalTime(arrivalTime);
            rideRequest.setStatus(RideRequest.RideStatus.PENDING);
            
            rideRequest = rideRequestRepository.save(rideRequest);
            
            responseBuilder.setRideRequestId(rideRequest.getRideRequestId())
                    .setSuccess(true)
                    .setMessage("Ride request registered successfully");
        } catch (Exception e) {
            responseBuilder.setSuccess(false)
                    .setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void getRideStatus(GetRideStatusRequest request,
                             StreamObserver<GetRideStatusResponse> responseObserver) {
        String rideRequestId = request.getRideRequestId();
        
        GetRideStatusResponse.Builder responseBuilder = GetRideStatusResponse.newBuilder();
        
        try {
            RideRequest rideRequest = rideRequestRepository.findById(rideRequestId)
                    .orElseThrow(() -> new RuntimeException("Ride request not found"));
            
            com.lastmile.rider.proto.RideStatus status = convertStatus(rideRequest.getStatus());
            
            responseBuilder.setRideRequestId(rideRequest.getRideRequestId())
                    .setStatus(status)
                    .setDriverId(rideRequest.getDriverId() != null ? rideRequest.getDriverId() : "")
                    .setTripId(rideRequest.getTripId() != null ? rideRequest.getTripId() : "")
                    .setSuccess(true);
        } catch (Exception e) {
            responseBuilder.setSuccess(false);
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void cancelRideRequest(CancelRideRequestRequest request,
                                 StreamObserver<CancelRideRequestResponse> responseObserver) {
        String rideRequestId = request.getRideRequestId();
        
        CancelRideRequestResponse.Builder responseBuilder = CancelRideRequestResponse.newBuilder();
        
        try {
            RideRequest rideRequest = rideRequestRepository.findById(rideRequestId)
                    .orElseThrow(() -> new RuntimeException("Ride request not found"));
            rideRequest.setStatus(RideRequest.RideStatus.CANCELLED);
            rideRequestRepository.save(rideRequest);
            
            responseBuilder.setSuccess(true)
                    .setMessage("Ride request cancelled successfully");
        } catch (Exception e) {
            responseBuilder.setSuccess(false)
                    .setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    private com.lastmile.rider.proto.RideStatus convertStatus(RideRequest.RideStatus status) {
        return switch (status) {
            case PENDING -> com.lastmile.rider.proto.RideStatus.PENDING;
            case MATCHED -> com.lastmile.rider.proto.RideStatus.MATCHED;
            case IN_PROGRESS -> com.lastmile.rider.proto.RideStatus.IN_PROGRESS;
            case COMPLETED -> com.lastmile.rider.proto.RideStatus.COMPLETED;
            case CANCELLED -> com.lastmile.rider.proto.RideStatus.CANCELLED;
        };
    }

    @Override
    public void listRideRequests(ListRideRequestsRequest request,
                                 StreamObserver<ListRideRequestsResponse> responseObserver) {
        String riderId = request.getRiderId();
        ListRideRequestsResponse.Builder builder = ListRideRequestsResponse.newBuilder();
        try {
            java.util.List<RideRequest> list = rideRequestRepository.findByRiderId(riderId);
            for (RideRequest rr : list) {
                RideRequestItem item = RideRequestItem.newBuilder()
                        .setRideRequestId(rr.getRideRequestId())
                        .setRiderId(rr.getRiderId())
                        .setMetroStation(rr.getMetroStation())
                        .setDestination(rr.getDestination())
                        .setArrivalTime(rr.getArrivalTime())
                        .setStatus(convertStatus(rr.getStatus()))
                        .setDriverId(rr.getDriverId() == null ? "" : rr.getDriverId())
                        .setTripId(rr.getTripId() == null ? "" : rr.getTripId())
                        .build();
                builder.addRideRequests(item);
            }
            builder.setSuccess(true);
        } catch (Exception e) {
            builder.setSuccess(false);
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void deleteRideRequest(DeleteRideRequestRequest request,
                                  StreamObserver<DeleteRideRequestResponse> responseObserver) {
        String rideRequestId = request.getRideRequestId();
        DeleteRideRequestResponse.Builder builder = DeleteRideRequestResponse.newBuilder();
        try {
            rideRequestRepository.deleteById(rideRequestId);
            builder.setSuccess(true).setMessage("Ride request deleted");
        } catch (Exception e) {
            builder.setSuccess(false).setMessage(e.getMessage());
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}

