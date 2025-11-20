package com.lastmile.driver.grpc;

import com.lastmile.driver.model.Driver;
import com.lastmile.driver.proto.*;
import com.lastmile.driver.repository.DriverRepository;
import com.lastmile.station.proto.StationServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.*;

@GrpcService
public class DriverGrpcService extends DriverServiceGrpc.DriverServiceImplBase {
    
    @Autowired
    private DriverRepository driverRepository;
    
    @GrpcClient("station-service")
    private StationServiceGrpc.StationServiceBlockingStub stationStub;
    
    @Override
    public void registerRoute(RegisterRouteRequest request,
                             StreamObserver<RegisterRouteResponse> responseObserver) {
        String driverId = request.getDriverId();
        String originStation = request.getOriginStation();
        String destination = request.getDestination();
        int availableSeats = request.getAvailableSeats();
        List<String> metroStations = new ArrayList<>(request.getMetroStationsList());
        
        List<String> routeStations = new ArrayList<>();
        
        try {
            com.lastmile.station.proto.GetStationsAlongRouteRequest stationRequest =
                    com.lastmile.station.proto.GetStationsAlongRouteRequest.newBuilder()
                            .setOrigin(originStation)
                            .setDestination(destination)
                            .build();
            
            com.lastmile.station.proto.GetStationsAlongRouteResponse stationResponse =
                    stationStub.getStationsAlongRoute(stationRequest);
            
            if (stationResponse.getSuccess()) {
                for (com.lastmile.station.proto.Station station : stationResponse.getStationsList()) {
                    routeStations.add(station.getStationId());
                }
            }
        } catch (Exception e) {
            if (!metroStations.isEmpty()) {
                routeStations = metroStations;
            }
        }
        
        if (routeStations.isEmpty() && !metroStations.isEmpty()) {
            routeStations = metroStations;
        }
        
        Driver driver = new Driver();
        driver.setDriverId(driverId);
        driver.setRouteId(UUID.randomUUID().toString());
        driver.setOriginStation(originStation);
        driver.setDestination(destination);
        driver.setAvailableSeats(availableSeats);
        driver.setMetroStations(routeStations);
        driver.setPickingUp(false);
        
        driver = driverRepository.save(driver);
        
        RegisterRouteResponse response = RegisterRouteResponse.newBuilder()
                .setRouteId(driver.getRouteId())
                .setSuccess(true)
                .setMessage("Route registered successfully")
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void updateLocation(UpdateLocationRequest request,
                              StreamObserver<UpdateLocationResponse> responseObserver) {
        String driverId = request.getDriverId();
        double latitude = request.getLatitude();
        double longitude = request.getLongitude();
        
        Driver driver = driverRepository.findById(driverId)
                .orElse(null);
        
        if (driver == null) {
            UpdateLocationResponse response = UpdateLocationResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Driver not found")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }
        
        Driver.Location location = new Driver.Location();
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setTimestamp(System.currentTimeMillis());
        driver.setCurrentLocation(location);
        
        driverRepository.save(driver);
        
        UpdateLocationResponse response = UpdateLocationResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Location updated successfully")
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void updatePickupStatus(UpdatePickupStatusRequest request,
                                  StreamObserver<UpdatePickupStatusResponse> responseObserver) {
        String driverId = request.getDriverId();
        boolean isPickingUp = request.getIsPickingUp();
        
        Driver driver = driverRepository.findById(driverId)
                .orElse(null);
        
        if (driver == null) {
            UpdatePickupStatusResponse response = UpdatePickupStatusResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Driver not found")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }
        
        driver.setPickingUp(isPickingUp);
        driverRepository.save(driver);
        
        UpdatePickupStatusResponse response = UpdatePickupStatusResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Pickup status updated successfully")
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
                    .setOriginStation(driver.getOriginStation())
                    .setDestination(driver.getDestination())
                    .setAvailableSeats(driver.getAvailableSeats())
                    .addAllMetroStations(driver.getMetroStations())
                    .setIsPickingUp(driver.isPickingUp())
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
        List<String> driverIds = driverRepository.findAll().stream()
                .map(Driver::getDriverId)
                .toList();
        
        ListDriversResponse response = ListDriversResponse.newBuilder()
                .addAllDriverIds(driverIds)
                .setSuccess(true)
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // --- Dashboard aggregation ---
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

        // Ensure lists not null
        if (driver.getActiveTrips() == null) driver.setActiveTrips(new ArrayList<>());
        if (driver.getRideHistory() == null) driver.setRideHistory(new ArrayList<>());

        // Compute earnings (total already stored; recalc safety if zero)
        int totalEarnings = driver.getTotalEarnings();
        if (totalEarnings == 0 && !driver.getRideHistory().isEmpty()) {
            totalEarnings = driver.getRideHistory().stream().mapToInt(Driver.TripRecord::getFare).sum();
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

        // Build active trips list
        for (Driver.TripRecord rec : driver.getActiveTrips()) {
            TripInfo info = TripInfo.newBuilder()
                    .setTripId(rec.getTripId())
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
                .setDriverRating(driver.getRating())
                .setTotalEarnings(totalEarnings)
                .setTodayEarnings(todayEarnings)
                .setYesterdayEarnings(yesterdayEarnings)
                .setOriginStation(driver.getOriginStation() == null ? "" : driver.getOriginStation())
                .setDestination(driver.getDestination() == null ? "" : driver.getDestination())
                .setAvailableSeats(driver.getAvailableSeats())
                .addAllMetroStations(driver.getMetroStations() == null ? List.of() : driver.getMetroStations())
                .setIsPickingUp(driver.isPickingUp());

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
        Driver driver = driverRepository.findById(request.getDriverId()).orElse(null);
        if (driver == null) {
            responseObserver.onNext(RateRiderResponse.newBuilder().setSuccess(false).setMessage("Driver not found").build());
            responseObserver.onCompleted();
            return;
        }
        if (driver.getRideHistory() == null) driver.setRideHistory(new ArrayList<>());
        boolean updated = false;
        for (Driver.TripRecord rec : driver.getRideHistory()) {
            if (rec.getTripId().equals(request.getTripId())) {
                rec.setRiderRatingGiven(request.getRating());
                updated = true;
                break;
            }
        }
        if (updated) driverRepository.save(driver);
        responseObserver.onNext(RateRiderResponse.newBuilder().setSuccess(updated).setMessage(updated?"Rating saved":"Trip not found").build());
        responseObserver.onCompleted();
    }
}

