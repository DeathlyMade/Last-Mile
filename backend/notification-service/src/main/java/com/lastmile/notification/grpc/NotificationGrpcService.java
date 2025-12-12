package com.lastmile.notification.grpc;

import com.lastmile.notification.proto.*;
import com.lastmile.notification.model.Notification;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

@GrpcService
public class NotificationGrpcService extends NotificationServiceGrpc.NotificationServiceImplBase {
    
    private static final Logger log = LoggerFactory.getLogger(NotificationGrpcService.class);
    
    @Autowired
    private RedisTemplate<String, Notification> redisTemplate;
    
    @Override
    public void sendNotification(SendNotificationRequest request,
                                 StreamObserver<SendNotificationResponse> responseObserver) {
        String userId = request.getUserId();
        String message = request.getMessage();
        com.lastmile.notification.proto.NotificationType type = request.getType();
        
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setMessage(message);
        notification.setType(type.name());
        notification.setTimestamp(System.currentTimeMillis());
        
        // Store in Redis list for the user
        redisTemplate.opsForList().rightPush("notifications:" + userId, notification);
        
        SendNotificationResponse response = SendNotificationResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Notification sent successfully")
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void sendMatchNotification(SendMatchNotificationRequest request,
                                     StreamObserver<SendMatchNotificationResponse> responseObserver) {
        String driverId = request.getDriverId();
        String riderId = request.getRiderId();
        String matchId = request.getMatchId();
        
        String driverMessage = "You have been matched with a rider. Match ID: " + matchId;
        String riderMessage = "You have been matched with a driver. Match ID: " + matchId;
        
        Notification driverNotification = new Notification();
        driverNotification.setUserId(driverId);
        driverNotification.setMessage(driverMessage);
        driverNotification.setType("MATCH");
        driverNotification.setTimestamp(System.currentTimeMillis());
        driverNotification.setMatchId(matchId);
        
        Notification riderNotification = new Notification();
        riderNotification.setUserId(riderId);
        riderNotification.setMessage(riderMessage);
        riderNotification.setType("MATCH");
        riderNotification.setTimestamp(System.currentTimeMillis());
        riderNotification.setMatchId(matchId);
        
        redisTemplate.opsForList().rightPush("notifications:" + driverId, driverNotification);
        redisTemplate.opsForList().rightPush("notifications:" + riderId, riderNotification);
        
        SendMatchNotificationResponse response = SendMatchNotificationResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Match notifications sent successfully")
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}

