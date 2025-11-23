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
    
    @Override
    public void registerRoute(RegisterRouteRequest request,
                             StreamObserver<RegisterRouteResponse> responseObserver) {
        String driverId = request.getDriverId();
        String destination = request.getDestination();
        int availableSeats = request.getAvailableSeats();
        List<String> metroStations = new ArrayList<>(request.getMetroStationsList());
        Driver driver = driverRepository.findById(driverId).orElse(new Driver());
        driver.setDriverId(driverId);
        driver.setRouteId(UUID.randomUUID().toString());
        driver.setDestination(destination);
        driver.setAvailableSeats(availableSeats);
        driver.setMetroStations(metroStations);
        
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
        
        Query query = new Query(Criteria.where("_id").is(driverId));
        Update update = new Update()
                .set("currentLocation.latitude", latitude)
                .set("currentLocation.longitude", longitude)
                .set("currentLocation.timestamp", System.currentTimeMillis());
        
        long modifiedCount = mongoTemplate.updateFirst(query, update, Driver.class).getModifiedCount();
        
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
    public void addTrip(AddTripRequest request, StreamObserver<AddTripResponse> responseObserver) {
        String driverId = request.getDriverId();
        
        Driver.TripRecord record = new Driver.TripRecord();
        record.setTripId(request.getTripId());
        record.setRiderId(request.getRiderId());
        record.setRiderName(request.getRiderName());
        record.setRiderRating(request.getRiderRating());
        record.setPickupStation(request.getPickupStation());
        record.setDestination(request.getDestination());
        record.setStatus(request.getStatus());
        record.setFare(request.getFare());
        record.setPickupTimestamp(request.getPickupTimestamp());
        Query query = new Query(Criteria.where("_id").is(driverId));
        Update update = new Update().push("activeTrips", record);
        
        long modifiedCount = mongoTemplate.updateFirst(query, update, Driver.class).getModifiedCount();
        
        AddTripResponse response = AddTripResponse.newBuilder()
                .setSuccess(modifiedCount > 0)
                .setMessage(modifiedCount > 0 ? "Trip added" : "Driver not found")
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
             drivers = driverRepository.findAll().stream()
                .filter(d -> d.getMetroStations() != null && d.getMetroStations().contains(station))
                .toList();
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

        // Ensure lists not null
        if (driver.getActiveTrips() == null) driver.setActiveTrips(new ArrayList<>());
        if (driver.getRideHistory() == null) driver.setRideHistory(new ArrayList<>());

        // Compute earnings (total already stored; recalc safety if zero)
        int totalEarnings = driver.getTotalEarnings();
        if (totalEarnings == 0 && !driver.getRideHistory().isEmpty()) {
            totalEarnings = driver.getRideHistory().stream().mapToInt(Driver.TripRecord::getFare).sum();
        }

        // Compute driver rating from history
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

        // Build active trips list
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
        double rating = request.getRating();
        
        // Update local driver record using atomic update
        Query query = new Query(Criteria.where("_id").is(driverId)
                .and("activeTrips.tripId").is(tripId));
        Update update = new Update().set("activeTrips.$.riderRating", rating);
        
        long modifiedCount = mongoTemplate.updateFirst(query, update, Driver.class).getModifiedCount();
        
        RateRiderResponse response = RateRiderResponse.newBuilder()
                .setSuccess(modifiedCount > 0)
                .setMessage(modifiedCount > 0 ? "Rider rated successfully" : "Trip not found")
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}

