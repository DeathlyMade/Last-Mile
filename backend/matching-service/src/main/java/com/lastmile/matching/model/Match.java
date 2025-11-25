package com.lastmile.matching.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "matches")
public class Match {
    @Id
    private String matchId;
    private String driverId;
    private String riderId;
    private String pickupStation;
    private String destination;
    private String tripId;
    private String status; // PENDING, MATCHED, CONFIRMED, CANCELLED
    private double fare;
    private long timestamp;
}
