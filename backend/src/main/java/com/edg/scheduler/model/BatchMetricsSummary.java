package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.util.UUID;

/**
 * 批次性能指标汇总实体类 - 对应数据库表 batch_metrics_summary
 *
 * 用于存储每个批次任务的性能指标汇总，供后续复盘分析和算法对比使用
 */
@Data
@Entity
@Table(name = "batch_metrics_summary")
public class BatchMetricsSummary {
    /** 唯一标识符 */
    @Id
    private String id;

    /** 批次ID */
    private String batchId;

    /** 使用的调度算法 */
    private String algorithm;

    /** 平均延迟（毫秒）*/
    private long latency;

    /** 总能耗（焦耳）*/
    private long energy;

    /** 表观带宽（MB/s）*/
    private long bandwidth;

    /** 成功率（%）*/
    private long successRate;

    /** 创建时间戳 */
    private long createdAt;

    /** 默认构造函数，自动生成 ID */
    public BatchMetricsSummary() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }
}
