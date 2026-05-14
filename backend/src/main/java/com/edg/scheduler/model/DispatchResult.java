package com.edg.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务分发结果数据传输对象
 *
 * 用于返回任务调度决策结果，包含成功/失败状态、消息、任务信息和分配的节点ID
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DispatchResult {
    /** 分发是否成功 */
    private boolean success;

    /** 结果消息 */
    private String message;

    /** 关联的任务信息 */
    private TaskInfo task;

    /** 分配的节点ID（如 CLOUD-SERVER 表示云端）*/
    private String assignedNodeId;

    /**
     * 创建成功结果
     * @param task 任务信息
     * @param nodeId 分配的节点ID
     * @return 成功结果
     */
    public static DispatchResult success(TaskInfo task, String nodeId) {
        return new DispatchResult(true, "Task dispatched successfully", task, nodeId);
    }

    /**
     * 创建失败结果
     * @param task 任务信息
     * @param message 失败原因
     * @return 失败结果
     */
    public static DispatchResult fail(TaskInfo task, String message) {
        return new DispatchResult(false, message, task, null);
    }
}
