package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Data;
import java.util.UUID;

/**
 * 操作员实体类 - 对应数据库表 operator
 *
 * 支持两种角色：
 * - ADMIN: 管理员，拥有全部权限（用户管理、节点增删、系统配置等）
 * - OPERATOR: 操作员，仅可操作任务和查看节点状态
 *
 * 认证方式：基于 Token 的简单认证，支持 24 小时过期时间
 */
@Data
@Entity
@Table(name = "operator")
public class Operator {
    /** 唯一标识符，UUID 格式 */
    @Id
    private String id;

    /** 用户名，唯一且不可为空 */
    @Column(unique = true, nullable = false)
    private String username;

    /** 密码，BCrypt 加密存储 */
    @Column(nullable = false)
    private String password;

    /** 角色：ADMIN 或 OPERATOR */
    @Column(nullable = false)
    private String role;

    /** 账户启用/禁用状态 */
    private boolean enabled;

    /** 坐标 X（用于任务起源地参考） */
    private double x;

    /** 坐标 Y（用于任务起源地参考） */
    private double y;

    /** 认证 Token */
    private String token;

    /** Token 过期时间戳（毫秒），0 表示永不过期 */
    private long tokenExpiryTime;

    /** 最后登录时间戳 */
    private long lastLoginTime;

    /** 账户创建时间戳 */
    private long createdTime;

    /** 默认构造函数 */
    public Operator() {
        this.id = UUID.randomUUID().toString();
        this.role = "OPERATOR";
        this.enabled = true;
        this.createdTime = System.currentTimeMillis();
    }

    /**
     * 完整构造函数
     * @param username 用户名
     * @param hashedPassword BCrypt 加密后的密码
     * @param x 初始坐标 X
     * @param y 初始坐标 Y
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
