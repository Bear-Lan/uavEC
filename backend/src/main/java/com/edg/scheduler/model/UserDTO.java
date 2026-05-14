package com.edg.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户数据传输对象（DTO）
 *
 * 用于 API 响应中返回用户信息，脱敏处理（不包含密码和 Token）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {
    /** 用户唯一标识 */
    private String id;

    /** 用户名 */
    private String username;

    /** 角色（ADMIN/OPERATOR）*/
    private String role;

    /** 账户启用状态 */
    private boolean enabled;

    /** 坐标 X */
    private double x;

    /** 坐标 Y */
    private double y;

    /** 最后登录时间戳 */
    private long lastLoginTime;

    /** 账户创建时间戳 */
    private long createdTime;

    /**
     * 从 Operator 实体转换为 DTO
     * @param operator 操作员实体
     * @return UserDTO 或 null（如果 operator 为空）
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
