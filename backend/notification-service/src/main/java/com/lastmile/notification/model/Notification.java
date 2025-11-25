package com.lastmile.notification.model;

import java.io.Serializable;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Notification implements Serializable {
    private String userId;
    private String message;
    private String type;
    private long timestamp;
    private String matchId;
    private String tripId;
}
