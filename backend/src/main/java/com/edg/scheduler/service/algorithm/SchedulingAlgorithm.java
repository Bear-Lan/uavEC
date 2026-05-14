package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import java.util.List;

/**
 * 调度算法接口
 *
 * 定义任务调度节点选择算法的标准接口。
 * 支持多种调度策略：贪心、加权公平队列、地理拓扑、自定义权重
 *
 * @see GreedyAlgorithm 贪心算法
 * @see WfqAlgorithm 加权公平队列算法
 * @see GeoAlgorithm 地理拓扑算法
 * @see CustomAlgorithm 自定义权重算法
 */
public interface SchedulingAlgorithm {
    /**
     * 获取算法名称
     * @return 算法标识（greedy / wfq / geo / custom）
     */
    String getName();

    /**
     * 从可用节点列表中选择最优节点
     *
     * @param availableNodes 可用无人机节点列表
     * @param task 待调度任务
     * @return 选中的最优节点，如果没有合适节点则返回 null
     */
    UAVNode selectNode(List<UAVNode> availableNodes, TaskInfo task);

    /**
     * 计算两点之间的欧几里得距离
     */
    default double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }
}
