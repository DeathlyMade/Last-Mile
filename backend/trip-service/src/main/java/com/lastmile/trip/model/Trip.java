package com.lastmile.trip.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "trips")
public class Trip {
    @Id
    private String tripId;
    private String driverId;
    private String riderId;
    private String pickupStation;
    private String destination;
    private TripStatus status;
    private long createdAt;
    private long pickupTime;
    private long dropoffTime;
    private String matchId;
    
    public enum TripStatus {
        SCHEDULED, ACTIVE, COMPLETED, CANCELLED
    }
}

