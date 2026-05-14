package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 任务信息实体类
 *
 * 表示边缘计算任务，包含任务属性和调度信息：
 * - 基础信息：id, taskName, type（IMAGE_PROCESSING/SENSOR_DATA/VIDEO_ANALYSIS）
 * - 资源需求：dataSize, requiredCpu, requiredMemory
 * - 调度配置：priority, schedulingAlgorithm, offloadAlgorithm, batchId
 * - 位置信息：originX, originY（任务来源坐标）
 * - 自定义权重：customW1, customW2, customW3（用于CustomAlgorithm）
 * - 执行状态：status, assignedUavId, submitTime, startTime, endTime
 * - 能耗追踪：actualEnergyUsed
 */
@Data
@Entity
@Table(name = "task_info")
public class TaskInfo {
    @Id
    private String id;

    private String taskName;

    private String type;

    private double dataSize;

    private double requiredCpu;

    private double requiredMemory;

    private int priority;

    @JsonProperty("originX")
    private double originX;

    @JsonProperty("originY")
    private double originY;

    private String offloadAlgorithm;

    private String schedulingAlgorithm;

    private String batchId;

    private String operatorName;

    private double actualEnergyUsed;

    private double customW1;

    private double customW2;

    private double customW3;

    private String status;

    private String assignedUavId;

    private long submitTime;

    private long startTime;

    private long endTime;

    /**
     * 默认构造函数
     */
    public TaskInfo() {
    }
}