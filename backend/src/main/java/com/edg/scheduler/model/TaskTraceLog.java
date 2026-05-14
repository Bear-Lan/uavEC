package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.util.UUID;

/**
 * 任务追踪日志实体类
 *
 * 记录任务全生命周期的关键时间点：
 * - taskId, taskName: 任务标识
 * - assignedUavId: 分配的节点
 * - createdTime: 任务创建时间
 * - dequeuedTime: 出队时间（计算queueLatency）
 * - executionStartTime: 执行开始时间（计算txLatency）
 * - executionEndTime: 执行结束时间（计算computeLatency）
 */
@Data
@Entity
@Table(name = "task_trace_log")
public class TaskTraceLog {
    @Id
    private String id;

    private String taskId;

    private String taskName;

    private String assignedUavId;

    private long createdTime;

    private long dequeuedTime;

    private long executionStartTime;

    private long executionEndTime;

    private long queueLatency;

    private long txLatency;

    private long computeLatency;

    /**
     * 默认构造函数
     *
     * 功能说明：
     * - 生成UUID作为唯一标识
     */
    public TaskTraceLog() {
        this.id = UUID.randomUUID().toString();
    }
}