package com.edg.scheduler.service.offloading;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;

/**
 * 卸载策略接口
 *
 * 定义边缘计算中任务卸载决策的标准接口：
 * - calculateOffloadingPath: 计算任务应该在本地、边缘还是云端执行
 *
 * 实现类：
 * @see LatencyOptimalStrategy 延迟最优策略（M/M/1排队论）
 * @see EnergyOptimalStrategy 能耗最优策略（DVFS）
 * @see AdaptivePartialStrategy 自适应部分卸载策略
 * @see DQNOffloadingStrategy 深度强化学习策略
 */
public interface OffloadingStrategy {

    /**
     * 计算卸载路径
     *
     * @param node 执行任务的无人机节点（边缘节点）
     * @param task 任务信息
     * @param cloud 云端状态信息
     * @return 卸载决策结果
     */
    OffloadResult calculateOffloadingPath(UAVNode node, TaskInfo task, CloudStatus cloud);

    /**
     * 获取策略名称
     */
    String getName();
}