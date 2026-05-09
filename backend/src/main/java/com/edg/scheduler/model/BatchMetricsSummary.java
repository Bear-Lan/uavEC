package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.UUID;

@Data
@Entity
@Table(name = "batch_metrics_summary")
public class BatchMetricsSummary {
    @Id
    private String id;
    private String batchId;
    private String algorithm;
    private long latency; // avg latency in ms
    private long energy; // total energy in J
    private long bandwidth; // apparent bandwidth MB/s
    private long successRate; // success %
    private long createdAt;

    public BatchMetricsSummary() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }
}
