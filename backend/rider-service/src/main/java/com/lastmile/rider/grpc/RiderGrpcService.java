package com.lastmile.rider.grpc;

import com.lastmile.rider.model.Rider;
import com.lastmile.rider.proto.*;
import com.lastmile.rider.repository.RiderRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.*;

@GrpcService
public class RiderGrpcService extends RiderServiceGrpc.RiderServiceImplBase {
    
    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private MongoTemplate mongoTemplate;
    
    @Override
    public void registerRideRequest(RegisterRideRequestRequest request,
                                   StreamObserver<RegisterRideRequestResponse> responseObserver) {
        String riderId = request.getRiderId();
        String metroStation = request.getMetroStation();
        String destination = request.getDestination();
        long arrivalTime = request.getArrivalTime();
        
        RegisterRideRequestResponse.Builder responseBuilder = RegisterRideRequestResponse.newBuilder();
        
        try {

            Rider rider = riderRepository.findById(riderId).orElse(new Rider());
            if (rider.getRiderId() == null) {
                rider.setRiderId(riderId);
                rider.setRating(5.0);
                rider.setTotalRides(0);
                rider.setRideHistory(new ArrayList<>());
            }

            Rider.RideRequest rideRequest = new Rider.RideRequest();
            rideRequest.setRideRequestId(UUID.randomUUID().toString());
            rideRequest.setMetroStation(metroStation);
            rideRequest.setDestination(destination);
            rideRequest.setArrivalTime(arrivalTime);
            rideRequest.setRequestTime(System.currentTimeMillis());
            rideRequest.setStatus(Rider.RideRequest.RideStatus.PENDING);
            
            rider.setCurrentRideRequest(rideRequest);
            
            riderRepository.save(rider);
            
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
        String riderId = request.getRiderId();
        
        GetRideStatusResponse.Builder responseBuilder = GetRideStatusResponse.newBuilder();
        
        try {
            Rider rider = riderRepository.findById(riderId).orElse(null);
            
            if (rider != null && rider.getCurrentRideRequest() != null) {
                Rider.RideRequest rr = rider.getCurrentRideRequest();
                com.lastmile.rider.proto.RideStatus status = convertStatus(rr.getStatus());
                
                responseBuilder.setRideRequestId(rr.getRideRequestId())
                        .setStatus(status)
                        .setDriverId(rr.getDriverId() != null ? rr.getDriverId() : "")
                        .setTripId(rr.getTripId() != null ? rr.getTripId() : "")
                        .setSuccess(true)
                        .setMessage("Ride status retrieved successfully");
            } else {
                responseBuilder.setSuccess(false).setMessage("No active ride request found for rider");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false).setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void cancelRideRequest(CancelRideRequestRequest request,
                                 StreamObserver<CancelRideRequestResponse> responseObserver) {
        String riderId = request.getRiderId();
        
        CancelRideRequestResponse.Builder responseBuilder = CancelRideRequestResponse.newBuilder();
        
        try {
            Rider rider = riderRepository.findById(riderId).orElse(null);
            
            if (rider != null && rider.getCurrentRideRequest() != null) {
                rider.setCurrentRideRequest(null);
                riderRepository.save(rider);
                
                responseBuilder.setSuccess(true)
                        .setMessage("Ride request cancelled successfully");
            } else {
                responseBuilder.setSuccess(false).setMessage("No active ride request found to cancel");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false)
                    .setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    private com.lastmile.rider.proto.RideStatus convertStatus(Rider.RideRequest.RideStatus status) {
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
            Rider rider = riderRepository.findById(riderId).orElse(null);
            if (rider != null) {

                if (rider.getCurrentRideRequest() != null) {
                    builder.addRideRequests(mapToProto(rider.getCurrentRideRequest(), riderId));
                }

                if (rider.getRideHistory() != null) {
                    for (Rider.RideRequest rr : rider.getRideHistory()) {
                        builder.addRideRequests(mapToProto(rr, riderId));
                    }
                }
            }
            builder.setSuccess(true);
        } catch (Exception e) {
            builder.setSuccess(false);
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    private RideRequestItem mapToProto(Rider.RideRequest rr, String riderId) {
        return RideRequestItem.newBuilder()
                .setRideRequestId(rr.getRideRequestId())
                .setRiderId(riderId)
                .setMetroStation(rr.getMetroStation())
                .setDestination(rr.getDestination())
                .setArrivalTime(rr.getArrivalTime())
                .setRequestTime(rr.getRequestTime())
                .setStatus(convertStatus(rr.getStatus()))
                .setDriverId(rr.getDriverId() == null ? "" : rr.getDriverId())
                .setTripId(rr.getTripId() == null ? "" : rr.getTripId())
                .setFare(rr.getFare())
                .setDriverRatingGiven(rr.getDriverRatingGiven())
                .setRiderRatingReceived(rr.getRiderRatingReceived())
                .setDropoffTime(rr.getDropoffTime())
                .build();
    }

    @Override
    public void matchedWithDriver(MatchedWithDriverRequest request,
                                StreamObserver<MatchedWithDriverResponse> responseObserver) {
        String riderId = request.getRiderId();
        String driverId = request.getDriverId();
        String tripId = request.getTripId();
        
        MatchedWithDriverResponse.Builder responseBuilder = MatchedWithDriverResponse.newBuilder();
        
        try {
            Rider rider = riderRepository.findById(riderId).orElse(null);
            
            if (rider != null && rider.getCurrentRideRequest() != null) {
                Rider.RideRequest rr = rider.getCurrentRideRequest();
                rr.setStatus(Rider.RideRequest.RideStatus.MATCHED);
                rr.setDriverId(driverId);
                rr.setTripId(tripId);
                
                riderRepository.save(rider);
                
                responseBuilder.setSuccess(true)
                        .setMessage("Rider matched with driver successfully");
            } else {
                responseBuilder.setSuccess(false).setMessage("Rider or active request not found");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false).setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void rideStarted(RideStartedRequest request,
                          StreamObserver<RideStartedResponse> responseObserver) {
        String riderId = request.getRiderId();
        
        RideStartedResponse.Builder responseBuilder = RideStartedResponse.newBuilder();
        
        try {
            Rider rider = riderRepository.findById(riderId).orElse(null);
            
            if (rider != null && rider.getCurrentRideRequest() != null) {
                Rider.RideRequest rr = rider.getCurrentRideRequest();
                rr.setStatus(Rider.RideRequest.RideStatus.IN_PROGRESS);
                
                riderRepository.save(rider);
                
                responseBuilder.setSuccess(true)
                        .setMessage("Ride started successfully");
            } else {
                responseBuilder.setSuccess(false).setMessage("Rider or active request not found");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false).setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void rideCompleted(RideCompletedRequest request,
                            StreamObserver<RideCompletedResponse> responseObserver) {
        String riderId = request.getRiderId();
        long dropoffTime = request.getDropoffTime();
        int fare = request.getFare();
        
        RideCompletedResponse.Builder responseBuilder = RideCompletedResponse.newBuilder();
        
        try {
            Rider rider = riderRepository.findById(riderId).orElse(null);
            
            if (rider != null && rider.getCurrentRideRequest() != null) {
                Rider.RideRequest rr = rider.getCurrentRideRequest();
                rr.setStatus(Rider.RideRequest.RideStatus.COMPLETED);
                rr.setDropoffTime(dropoffTime);
                rr.setFare(fare);
                
                if (rider.getRideHistory() == null) {
                    rider.setRideHistory(new ArrayList<>());
                }
                rider.getRideHistory().add(rr);
                
                rider.setCurrentRideRequest(null);
                rider.setTotalRides(rider.getTotalRides() + 1);
                
                riderRepository.save(rider);
                
                responseBuilder.setSuccess(true)
                        .setMessage("Ride completed successfully");
            } else {
                responseBuilder.setSuccess(false).setMessage("Rider or active request not found");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false).setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getRiderDashboard(GetRiderDashboardRequest request,
                                StreamObserver<GetRiderDashboardResponse> responseObserver) {
        String riderId = request.getRiderId();
        GetRiderDashboardResponse.Builder responseBuilder = GetRiderDashboardResponse.newBuilder();

        try {
            Rider rider = riderRepository.findById(riderId).orElse(null);
            if (rider != null) {
                responseBuilder.setRiderId(rider.getRiderId())
                        .setRating(rider.getRating())
                        .setTotalRides(rider.getTotalRides())
                        .setSuccess(true);

                if (rider.getCurrentRideRequest() != null) {
                    responseBuilder.setCurrentRide(mapToProto(rider.getCurrentRideRequest(), riderId));
                }

                if (rider.getRideHistory() != null) {
                    for (Rider.RideRequest rr : rider.getRideHistory()) {
                        responseBuilder.addRideHistory(mapToProto(rr, riderId));
                    }
                }
            } else {
                responseBuilder.setSuccess(false);
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false);
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getRiderInfo(GetRiderInfoRequest request,
                           StreamObserver<GetRiderInfoResponse> responseObserver) {
        String riderId = request.getRiderId();
        GetRiderInfoResponse.Builder responseBuilder = GetRiderInfoResponse.newBuilder();

        try {
            Rider rider = riderRepository.findById(riderId).orElse(null);
            if (rider != null) {
                responseBuilder.setRiderId(rider.getRiderId())
                        .setRating(rider.getRating())
                        .setTotalRides(rider.getTotalRides())
                        .setSuccess(true);
            } else {
                responseBuilder.setSuccess(false);
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false);
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void rateDriver(RateDriverRequest request,
                         StreamObserver<RateDriverResponse> responseObserver) {
        String riderId = request.getRiderId();
        String tripId = request.getTripId();
        int rating = request.getRating();

        RateDriverResponse.Builder responseBuilder = RateDriverResponse.newBuilder();
        
        try {
            if (tripId == null || tripId.isEmpty()) {
                responseBuilder.setSuccess(false).setMessage("Trip ID is required");
            } else {
                Rider rider = riderRepository.findById(riderId).orElse(null);
                if (rider != null) {
                    boolean tripFound = false;
                    // Check history for the trip
                    if (rider.getRideHistory() != null) {
                        for (Rider.RideRequest rr : rider.getRideHistory()) {
                            if (tripId.equals(rr.getTripId())) {
                                rr.setDriverRatingGiven(rating);
                                tripFound = true;
                                break;
                            }
                        }
                    }
                    // Check current request (unlikely for rating, but possible if not moved to history yet)
                    if (!tripFound && rider.getCurrentRideRequest() != null && tripId.equals(rider.getCurrentRideRequest().getTripId())) {
                        rider.getCurrentRideRequest().setDriverRatingGiven(rating);
                        tripFound = true;
                    }
                    
                    if (tripFound) {
                        riderRepository.save(rider);
                        responseBuilder.setSuccess(true).setMessage("Driver rated successfully");
                    } else {
                        responseBuilder.setSuccess(false).setMessage("Trip not found for this rider");
                    }
                } else {
                    responseBuilder.setSuccess(false).setMessage("Rider not found");
                }
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false).setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void setRatingFromDriver(SetRatingFromDriverRequest request,
                                  StreamObserver<SetRatingFromDriverResponse> responseObserver) {
        String riderId = request.getRiderId();
        String tripId = request.getTripId();
        int newRating = request.getRating();
        
        SetRatingFromDriverResponse.Builder responseBuilder = SetRatingFromDriverResponse.newBuilder();
        
        try {
            Rider rider = riderRepository.findById(riderId).orElse(null);
            if (rider != null) {
                boolean found = false;
                if (rider.getRideHistory() != null) {
                    for (Rider.RideRequest rr : rider.getRideHistory()) {
                        if (rr.getTripId() != null && rr.getTripId().equals(tripId)) {
                            rr.setRiderRatingReceived(newRating);
                            found = true;
                            break;
                        }
                    }
                }
                
                if (found) {
                    double totalRating = 0;
                    int ratedRidesCount = 0;
                    
                    if (rider.getRideHistory() != null) {
                        for (Rider.RideRequest rr : rider.getRideHistory()) {
                            if (rr.getRiderRatingReceived() > 0) {
                                totalRating += rr.getRiderRatingReceived();
                                ratedRidesCount++;
                            }
                        }
                    }
                    
                    if (ratedRidesCount > 0) {
                        rider.setRating(totalRating / ratedRidesCount);
                    } else {
                        rider.setRating(newRating);
                    }
                    
                    riderRepository.save(rider);
                    responseBuilder.setSuccess(true).setMessage("Rating updated");
                } else {
                    responseBuilder.setSuccess(false).setMessage("Trip not found in rider history");
                }
            } else {
                responseBuilder.setSuccess(false).setMessage("Rider not found");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false).setMessage(e.getMessage());
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}


