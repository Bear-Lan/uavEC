package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.util.UUID;

/**
 * 任务追踪日志实体类 - 对应数据库表 task_trace_log
 *
 * 记录任务在整个生命周期中的关键时间节点，用于：
 * - 性能分析和延迟追踪
 * - 调度决策验证
 * - 问题诊断和根因分析
 *
 * 生命周期阶段：
 * createdTime -> dequeuedTime -> executionStartTime -> executionEndTime
 *
 * 延迟计算：
 * - queueLatency = dequeuedTime - createdTime（排队延迟）
 * - txLatency = executionStartTime - dequeuedTime（传输延迟）
 * - computeLatency = executionEndTime - executionStartTime（计算延迟）
 */
@Data
@Entity
@Table(name = "task_trace_log")
public class TaskTraceLog {
    /** 唯一标识符 */
    @Id
    private String id;

    /** 关联的任务ID */
    private String taskId;

    /** 任务名称 */
    private String taskName;

    /** 分配的节点ID */
    private String assignedUavId;

    /** 任务创建时间戳 */
    private long createdTime;

    /** 任务出队时间戳 */
    private long dequeuedTime;

    /** 任务开始执行时间戳 */
    private long executionStartTime;

    /** 任务结束执行时间戳 */
    private long executionEndTime;

    /** 排队延迟（毫秒）= dequeuedTime - createdTime */
    private long queueLatency;

    /** 传输延迟（毫秒）= executionStartTime - dequeuedTime */
    private long txLatency;

    /** 计算延迟（毫秒）= executionEndTime - executionStartTime */
    private long computeLatency;

    /** 默认构造函数 */
    public TaskTraceLog() {
        this.id = UUID.randomUUID().toString();
    }
}
