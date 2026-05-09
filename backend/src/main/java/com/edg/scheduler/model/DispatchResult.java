package com.edg.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DispatchResult {
    private boolean success;
    private String message;
    private TaskInfo task;
    private String assignedNodeId;
    
    public static DispatchResult success(TaskInfo task, String nodeId) {
        return new DispatchResult(true, "Task dispatched successfully", task, nodeId);
    }
    
    public static DispatchResult fail(TaskInfo task, String message) {
        return new DispatchResult(false, message, task, null);
    }
}
