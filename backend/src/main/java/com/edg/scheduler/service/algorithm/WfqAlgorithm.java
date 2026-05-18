package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 加权公平队列调度算法 (Weighted Fair Queueing)
 *
 * 策略：按节点权重（maxCpu）比例分配任务名额
 * 公式：deficit = (weight_i / sum(weights)) * totalActiveTasks - activeTasksCount_i
 * 选择 deficit 最大的节点（最缺任务的节点优先）
 *
 * 优点：负载按权重公平分配，权重高的节点获得更多任务
 * 缺点：需要计算全局统计
 */
@Component("wfqAlgorithm")
public class WfqAlgorithm implements SchedulingAlgorithm {
    @Override
    public String getName() {
        return "wfq";
    }

    @Override
    public UAVNode selectNode(List<UAVNode> availableNodes, TaskInfo task) {
        if (availableNodes == null || availableNodes.isEmpty()) {
            return null;
        }

        double totalWeight = availableNodes.stream()
                .mapToDouble(UAVNode::getMaxCpu)
                .sum();

        if (totalWeight <= 0) {
            return availableNodes.stream()
                    .min(Comparator.comparingInt(n -> n.getActiveTasksCount().get()))
                    .orElse(null);
        }

        int totalActiveTasks = availableNodes.stream()
                .mapToInt(n -> n.getActiveTasksCount().get())
                .sum();

        return availableNodes.stream()
                .max(Comparator.comparingDouble(n -> {
                    double weight = n.getMaxCpu();
                    double fairShare = (weight / totalWeight) * totalActiveTasks;
                    double deficit = fairShare - n.getActiveTasksCount().get();
                    return deficit;
                }))
                .orElse(null);
    }
}
