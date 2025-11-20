package com.lastmile.user.grpc;

import com.lastmile.user.model.User;
import com.lastmile.user.proto.*;
import com.lastmile.user.repository.UserRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Override
    public void registerUser(RegisterUserRequest request,
                            StreamObserver<RegisterUserResponse> responseObserver) {
        String email = request.getEmail();
        String password = request.getPassword();
        String name = request.getName();
        com.lastmile.user.proto.UserType userType = request.getUserType();
        String phone = request.getPhone();
        
        RegisterUserResponse.Builder responseBuilder = RegisterUserResponse.newBuilder();
        
        try {
            if (userRepository.findByEmail(email).isPresent()) {
                responseBuilder.setSuccess(false)
                        .setMessage("User already exists");
            } else {
                User user = new User();
                user.setUserId(UUID.randomUUID().toString());
                user.setEmail(email);
                user.setPassword(password);
                user.setName(name);
                user.setUserType(userType == com.lastmile.user.proto.UserType.DRIVER ? 
                    User.UserType.DRIVER : User.UserType.RIDER);
                user.setPhone(phone);
                
                user = userRepository.save(user);
                
                String token = UUID.randomUUID().toString();
                redisTemplate.opsForValue().set("token:" + token, user.getUserId(), 1, TimeUnit.HOURS);
                
                responseBuilder.setUserId(user.getUserId())
                        .setToken(token)
                        .setSuccess(true)
                        .setMessage("User registered successfully");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false)
                    .setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void loginUser(LoginUserRequest request,
                         StreamObserver<LoginUserResponse> responseObserver) {
        String email = request.getEmail();
        String password = request.getPassword();
        
        LoginUserResponse.Builder responseBuilder = LoginUserResponse.newBuilder();
        
        try {
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty() || !userOpt.get().getPassword().equals(password)) {
                responseBuilder.setSuccess(false)
                        .setMessage("Invalid credentials");
            } else {
                User user = userOpt.get();
                String token = UUID.randomUUID().toString();
                redisTemplate.opsForValue().set("token:" + token, user.getUserId(), 1, TimeUnit.HOURS);
                
                responseBuilder.setUserId(user.getUserId())
                        .setToken(token)
                        .setSuccess(true)
                        .setMessage("Login successful");
            }
        } catch (Exception e) {
            responseBuilder.setSuccess(false)
                    .setMessage(e.getMessage());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void getUserProfile(GetUserProfileRequest request,
                              StreamObserver<GetUserProfileResponse> responseObserver) {
        String userId = request.getUserId();
        
        GetUserProfileResponse.Builder responseBuilder = GetUserProfileResponse.newBuilder();
        
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            
            com.lastmile.user.proto.UserType userType = user.getUserType() == User.UserType.DRIVER ?
                    com.lastmile.user.proto.UserType.DRIVER : com.lastmile.user.proto.UserType.RIDER;
            
                responseBuilder.setUserId(user.getUserId())
                    .setEmail(user.getEmail())
                    .setName(user.getName())
                    .setPhone(user.getPhone() == null ? "" : user.getPhone())
                    .setUserType(userType)
                    .setSuccess(true);
        } catch (Exception e) {
            responseBuilder.setSuccess(false);
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
//    @Override
//    public void updateUserProfile(UpdateUserProfileRequest request,
//                                 StreamObserver<UpdateUserProfileResponse> responseObserver) {
//        String userId = request.getUserId();
//        String name = request.getName();
//
//        UpdateUserProfileResponse.Builder responseBuilder = UpdateUserProfileResponse.newBuilder();
//
//        try {
//            User user = userRepository.findById(userId)
//                    .orElseThrow(() -> new RuntimeException("User not found"));
//            user.setName(name);
//            userRepository.save(user);
//
//            responseBuilder.setSuccess(true)
//                    .setMessage("Profile updated successfully");
//        } catch (Exception e) {
//            responseBuilder.setSuccess(false)
//                    .setMessage(e.getMessage());
//        }
//
//        responseObserver.onNext(responseBuilder.build());
//        responseObserver.onCompleted();
//    }
}

