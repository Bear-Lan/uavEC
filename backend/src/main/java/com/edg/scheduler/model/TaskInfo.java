package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 任务信息实体类 - 对应数据库表 task_info
 *
 * 任务状态机：
 * QUEUED -> DISPATCHING -> RUNNING_EDGE | RUNNING_CLOUD | RUNNING_SPLIT -> COMPLETED | FAILED
 *
 * 支持多种卸载策略：
 * - threshold: 仅大任务（>500MB）卸载到云端
 * - energy: 能耗感知决策
 * - latency: 延迟最优决策
 *
 * 支持多种调度算法：
 * - greedy: 贪心算法，选择剩余 CPU 最多的节点
 * - wfq: 加权公平队列，选择活跃任务最少的节点
 * - geo: 地理拓扑算法，综合考虑 CPU、电池、距离
 * - custom: 自定义权重算法
 */
@Data
@Entity
@Table(name = "task_info")
public class TaskInfo {
    /** 任务唯一标识符 */
    @Id
    private String id;

    /** 任务名称 */
    private String taskName;

    /** 任务类型：IMAGE_PROCESSING / SENSOR_DATA / VIDEO_ANALYSIS */
    private String type;

    /** 数据大小（MB）*/
    private double dataSize;

    /** 所需 CPU 核心数 */
    private double requiredCpu;

    /** 所需内存（MB）*/
    private double requiredMemory;

    /** 优先级（1-5，>=4 为高优先级任务） */
    private int priority;

    /** 任务起源坐标 X */
    @JsonProperty("originX")
    private double originX;

    /** 任务起源坐标 Y */
    @JsonProperty("originY")
    private double originY;

    /** 卸载算法：threshold / energy / latency */
    private String offloadAlgorithm;

    /** 调度算法覆盖：greedy / wfq / geo / custom */
    private String schedulingAlgorithm;

    /** 批次 ID，标识同一批次提交的任务 */
    private String batchId;

    /** 提交任务的操作员用户名 */
    private String operatorName;

    /** 实际消耗的能量（焦耳）*/
    private double actualEnergyUsed;

    /** 自定义算法权重：距离权重 */
    private double customW1;

    /** 自定义算法权重：CPU 权重 */
    private double customW2;

    /** 自定义算法权重：电池权重 */
    private double customW3;

    /** 任务状态 */
    private String status;

    /** 分配到的无人机节点 ID */
    private String assignedUavId;

    /** 任务提交时间戳 */
    private long submitTime;

    /** 任务开始执行时间戳 */
    private long startTime;

    /** 任务结束时间戳 */
    private long endTime;

    /** 默认构造函数 */
    public TaskInfo() {
        // id, submitTime, status 由 TaskService.submitTask() 设置
    }
}
