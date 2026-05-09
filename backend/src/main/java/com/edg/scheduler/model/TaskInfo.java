package com.edg.scheduler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Entity
@Table(name = "task_info")
public class TaskInfo {
    @Id
    private String id;
    private String taskName;
    private String type;
    private double dataSize; // in MB
    private double requiredCpu; // required cores
    private double requiredMemory; // required RAM in MB
    private int priority; // 1-5

    // Phase 3 attributes
    @JsonProperty("originX")
    private double originX; // Task generation X
    @JsonProperty("originY")
    private double originY; // Task generation Y

    // Phase 4 attributes
    private String offloadAlgorithm; // threshold, energy, latency
    private String schedulingAlgorithm; // greedy, wfq, geo — per-task override

    // Phase 6 attributes for real telemetry
    private String batchId; // Identifies a single burst load
    private String operatorName; // Username of the operator who submitted this task
    private double actualEnergyUsed; // Accumulated physical energy in Joules

    // Custom Algorithm Weights (Phase 5)
    private double customW1; // Distance weight
    private double customW2; // CPU weight
    private double customW3; // Battery weight

    // Status tracking
    // State machine: QUEUED -> DISPATCHING -> RUNNING_EDGE | RUNNING_CLOUD ->
    // COMPLETED | FAILED
    private String status;
    private String assignedUavId;
    private long submitTime;
    private long startTime;
    private long endTime;

    public TaskInfo() {
        // Intentionally empty — id, submitTime, status are set by
        // TaskService.submitTask()
    }
}
