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
    private String originStation;
    private String destination;
    private int availableSeats;
    private List<String> metroStations;
    private boolean isPickingUp;
    private Location currentLocation;
    // Dashboard fields
    private double rating; // aggregated driver rating
    private int totalEarnings; // cumulative earnings (in rupees)
    private java.util.List<TripRecord> activeTrips; // active or scheduled trips
    private java.util.List<TripRecord> rideHistory; // completed trips
    
    @Data
    public static class Location {
        private double latitude;
        private double longitude;
        private long timestamp;
    }

    @Data
    public static class TripRecord {
        private String tripId;
        private String riderName;
        private double riderRating;
        private String pickupStation;
        private String destination;
        private String status; // scheduled | active | completed
        private int fare;
        private long pickupTimestamp; // epoch ms
        private long dropoffTimestamp; // epoch ms (if completed)
        private int riderRatingGiven; // rating given by driver to rider (1-5)
    }
}

