package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Data;

import java.util.UUID;

@Data
@Entity
@Table(name = "operator")
public class Operator {
    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password; // BCrypt hashed

    @Column(nullable = false)
    private String role; // ADMIN, OPERATOR

    private boolean enabled; // 账户启用状态

    private double x;
    private double y;
    private String token; // For simple auth
    private long tokenExpiryTime; // Token expiration timestamp
    private long lastLoginTime;
    private long createdTime;

    public Operator() {
        this.id = UUID.randomUUID().toString();
        this.role = "OPERATOR";
        this.enabled = true;
        this.createdTime = System.currentTimeMillis();
    }

    public Operator(String username, String hashedPassword, double x, double y) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
        this.password = hashedPassword;
        this.role = "OPERATOR";
        this.enabled = true;
        this.x = x;
        this.y = y;
        this.lastLoginTime = System.currentTimeMillis();
        this.createdTime = System.currentTimeMillis();
    }
}
