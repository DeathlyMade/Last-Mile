package com.lastmile.user.grpc;

import com.lastmile.user.model.User;
import com.lastmile.user.proto.*;
import com.lastmile.user.repository.UserRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {
    
    private static final Logger log = LoggerFactory.getLogger(UserGrpcService.class);
    
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
        
        log.info("Received user registration request for email: {}", email);
        
        RegisterUserResponse.Builder responseBuilder = RegisterUserResponse.newBuilder();
        
        try {
            if (userRepository.findByEmail(email).isPresent()) {
                log.warn("Registration failed - user already exists: {}", email);
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
                log.info("User registered successfully - userId: {}, name: {}, type: {}", 
                    user.getUserId(), user.getName(), user.getUserType());
                
                String token = UUID.randomUUID().toString();
                redisTemplate.opsForValue().set("token:" + token, user.getUserId(), 1, TimeUnit.HOURS);
                log.debug("Generated authentication token for user: {}", user.getUserId());
                
                responseBuilder.setUserId(user.getUserId())
                        .setToken(token)
                        .setSuccess(true)
                        .setMessage("User registered successfully");
            }
        } catch (Exception e) {
            log.error("Error during user registration for email: {}", email, e);
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
        
        log.info("Login attempt for email: {}", email);
        
        LoginUserResponse.Builder responseBuilder = LoginUserResponse.newBuilder();
        
        try {
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty() || !userOpt.get().getPassword().equals(password)) {
                log.warn("Login failed for email: {} - Invalid credentials", email);
                responseBuilder.setSuccess(false)
                        .setMessage("Invalid credentials");
            } else {
                User user = userOpt.get();
                String token = UUID.randomUUID().toString();
                redisTemplate.opsForValue().set("token:" + token, user.getUserId(), 1, TimeUnit.HOURS);
                
                log.info("User logged in successfully - userId: {}, email: {}", user.getUserId(), email);
                
                responseBuilder.setUserId(user.getUserId())
                        .setToken(token)
                        .setSuccess(true)
                        .setMessage("Login successful");
            }
        } catch (Exception e) {
            log.error("Error during login for email: {}", email, e);
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

    @Override
    public void logoutUser(LogoutUserRequest request, StreamObserver<LogoutUserResponse> responseObserver) {
        LogoutUserResponse.Builder builder = LogoutUserResponse.newBuilder();
        try {
            // Retrieve auth token from static key set by AuthInterceptor.
            String token = AuthInterceptor.AUTH_TOKEN_KEY.get();
            if (token == null) {
                builder.setSuccess(false).setMessage("No token in context");
            } else {
                String redisKey = "token:" + token;
                String existing = redisTemplate.opsForValue().get(redisKey);
                Boolean deleted = redisTemplate.delete(redisKey);
                builder.setSuccess(true).setMessage(deleted != null && deleted ? "Logged out" : "Token already invalidated");
            }
        } catch (Exception e) {
            builder.setSuccess(false).setMessage(e.getMessage());
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}

