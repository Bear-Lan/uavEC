package com.edg.scheduler.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 操作日志实体类 - 对应数据库表 operation_log
 *
 * 记录所有 API 操作行为，包括：
 * - 请求用户信息（用户名、角色）
 * - 请求路径和 HTTP 方法
 * - 客户端 IP 和 User-Agent
 * - 请求参数（脱敏处理）
 * - 响应状态和错误信息
 *
 * 由 OperationLogAspect 切面自动记录
 */
@Entity
@Table(name = "operation_log")
public class OperationLog {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户名 */
    @Column(nullable = false)
    private String username;

    /** 角色 */
    @Column(nullable = false)
    private String role;

    /** 操作描述（可选）*/
    @Column(nullable = false)
    private String action;

    /** 请求资源路径 */
    @Column
    private String resource;

    /** HTTP 方法 */
    @Column
    private String method;

    /** 客户端 IP 地址 */
    @Column
    private String ipAddress;

    /** User-Agent 信息 */
    @Column
    private String userAgent;

    /** 请求参数（脱敏处理后）*/
    @Column
    private String requestParams;

    /** 响应状态码 */
    @Column
    private Integer responseStatus;

    /** 错误信息（如有）*/
    @Column
    private String errorMessage;

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createTime;

    /** 持久化前自动设置创建时间 */
    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getRequestParams() { return requestParams; }
    public void setRequestParams(String requestParams) { this.requestParams = requestParams; }

    public Integer getResponseStatus() { return responseStatus; }
    public void setResponseStatus(Integer responseStatus) { this.responseStatus = responseStatus; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}