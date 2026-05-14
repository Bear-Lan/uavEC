package com.edg.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务分发结果数据传输对象
 *
 * 表示任务调度决策的结果：
 * - success: 分发是否成功
 * - message: 结果描述信息
 * - task: 关联的任务对象
 * - assignedNodeId: 分配的节点ID（可能为CLOUD-SERVER）
 * - 提供success/fail工厂方法
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DispatchResult {
    private boolean success;

    private String message;

    private TaskInfo task;

    private String assignedNodeId;

    /**
     * 创建成功结果
     *
     * @param task 关联任务
     * @param nodeId 分配的节点ID
     * @return 成功结果对象
     */
    public static DispatchResult success(TaskInfo task, String nodeId) {
        return new DispatchResult(true, "Task dispatched successfully", task, nodeId);
    }

    /**
     * 创建失败结果
     *
     * @param task 关联任务
     * @param message 失败原因
     * @return 失败结果对象
     */
    public static DispatchResult fail(TaskInfo task, String message) {
        return new DispatchResult(false, message, task, null);
    }
}