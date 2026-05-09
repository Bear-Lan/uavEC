package com.edg.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {
    private String id;
    private String username;
    private String role;
    private boolean enabled;
    private double x;
    private double y;
    private long lastLoginTime;
    private long createdTime;

    public static UserDTO fromEntity(Operator operator) {
        if (operator == null)
            return null;
        return new UserDTO(
                operator.getId(),
                operator.getUsername(),
                operator.getRole(),
                operator.isEnabled(),
                operator.getX(),
                operator.getY(),
                operator.getLastLoginTime(),
                operator.getCreatedTime());
    }
}
