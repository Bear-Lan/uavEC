package com.edg.scheduler.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 操作日志实体类
 *
 * 记录用户操作行为，用于审计和追踪：
 * - 用户信息：username, role
 * - 操作信息：action, resource, method
 * - 请求信息：ipAddress, userAgent, requestParams
 * - 响应信息：responseStatus, errorMessage
 * - 时间戳：createTime（自动填充）
 */
@Entity
@Table(name = "operation_log")
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String action;

    @Column
    private String resource;

    @Column
    private String method;

    @Column
    private String ipAddress;

    @Column
    private String userAgent;

    @Column
    private String requestParams;

    @Column
    private Integer responseStatus;

    @Column
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    /**
     * 持久化前自动设置创建时间
     */
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }

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