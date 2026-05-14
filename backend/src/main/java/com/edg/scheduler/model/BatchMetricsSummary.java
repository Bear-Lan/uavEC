package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.util.UUID;

/**
 * 批次性能指标汇总实体类
 *
 * 记录一批任务的性能统计数据：
 * - batchId: 批次唯一标识
 * - algorithm: 使用的调度算法
 * - latency: 平均延迟（毫秒）
 * - energy: 总能耗
 * - bandwidth: 带宽消耗
 * - successRate: 成功率
 * - createdAt: 创建时间戳
 */
@Data
@Entity
@Table(name = "batch_metrics_summary")
public class BatchMetricsSummary {
    @Id
    private String id;

    private String batchId;

    private String algorithm;

    private long latency;

    private long energy;

    private long bandwidth;

    private long successRate;

    private long createdAt;

    /**
     * 默认构造函数
     *
     * 功能说明：
     * - 生成UUID作为唯一标识
     * - 设置创建时间戳
     */
    public BatchMetricsSummary() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }
}