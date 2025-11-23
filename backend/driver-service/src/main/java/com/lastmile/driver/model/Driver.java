package com.lastmile.driver.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Document(collection = "drivers")
public class Driver {
    @Id
    private String driverId;
    private String routeId;
    private String destination;
    private int availableSeats;
    private List<String> metroStations;
    private Location currentLocation;
    private double rating; 
    private int totalEarnings; 
    private java.util.List<TripRecord> activeTrips; 
    private java.util.List<TripRecord> rideHistory; 
    
    @Data
    public static class Location {
        private double latitude;
        private double longitude;
        private long timestamp;
    }

    @Data
    public static class TripRecord {
        private String tripId;
        private String riderId; 
        private String riderName;
        private double riderRating;
        private String pickupStation;
        private String destination;
        private String status; // scheduled | active | completed
        private int fare;
        private long pickupTimestamp; // epoch ms
        private long dropoffTimestamp; // epoch ms (if completed)
        private int riderRatingGiven; // rating given by driver to rider (1-5)
        private int driverRatingReceived; // rating received by driver from rider (1-5)
    }
}

