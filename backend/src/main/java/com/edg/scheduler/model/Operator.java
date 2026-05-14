package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Data;
import java.util.UUID;

/**
 * 操作员实体类
 *
 * 表示系统中的用户操作员，包含认证和授权信息：
 * - id: 操作员唯一标识（UUID）
 * - username: 用户名（唯一）
 * - password: BCrypt加密密码
 * - role: 角色（ADMIN/OPERATOR）
 * - enabled: 账户启用状态
 * - x, y: 用户地理位置坐标
 * - token: 认证令牌
 * - tokenExpiryTime: 令牌过期时间
 * - lastLoginTime: 最后登录时间
 * - createdTime: 账户创建时间
 */
@Data
@Entity
@Table(name = "operator")
public class Operator {
    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role;

    private boolean enabled;

    private double x;

    private double y;

    private String token;

    private long tokenExpiryTime;

    private long lastLoginTime;

    private long createdTime;

    /**
     * 默认构造函数
     *
     * 功能说明：
     * - 生成UUID作为唯一标识
     * - 设置默认角色为OPERATOR
     * - 设置账户默认启用
     * - 记录创建时间
     */
    public Operator() {
        this.id = UUID.randomUUID().toString();
        this.role = "OPERATOR";
        this.enabled = true;
        this.createdTime = System.currentTimeMillis();
    }

    /**
     * 带参构造函数
     *
     * @param username 用户名
     * @param hashedPassword 已加密的密码
     * @param x X坐标
     * @param y Y坐标
     */
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