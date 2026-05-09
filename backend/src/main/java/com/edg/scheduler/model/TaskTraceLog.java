package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.util.UUID;

@Data
@Entity
@Table(name = "task_trace_log")
public class TaskTraceLog {
    @Id
    private String id;
    private String taskId;
    private String taskName;
    private String assignedUavId;

    // Timestamps
    private long createdTime;
    private long dequeuedTime;
    private long executionStartTime;
    private long executionEndTime;

    // Latencies (calculated)
    private long queueLatency; // dequeuedTime - createdTime
    private long txLatency; // executionStartTime - dequeuedTime
    private long computeLatency; // executionEndTime - executionStartTime

    public TaskTraceLog() {
        this.id = UUID.randomUUID().toString();
    }
}
