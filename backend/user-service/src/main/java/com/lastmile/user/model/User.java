package com.lastmile.user.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "users")
public class User {
    @Id
    private String userId;
    private String email;
    private String password;
    private String name;
    private String phone; // Added phone field
    private UserType userType;
    
    public enum UserType {
        RIDER, DRIVER
    }
}

