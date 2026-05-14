package com.edg.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户数据传输对象
 *
 * 用于前后端数据交换的用户信息载体：
 * - 包含id, username, role, enabled等业务字段
 * - 包含x, y坐标用于地图展示
 * - 包含lastLoginTime, createdTime用于审计
 * - 提供fromEntity静态方法将Operator实体转换为DTO
 */
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

    /**
     * 将Operator实体转换为UserDTO
     *
     * @param operator 操作员实体
     * @return 用户DTO（如果实体为null则返回null）
     */
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