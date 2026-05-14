package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 贪心调度算法
 *
 * 策略：选择剩余 CPU 资源最多的节点
 *
 * 优点：简单高效，适合 CPU 密集型任务
 * 缺点：可能忽略电池、距离等因素
 */
@Component("greedyAlgorithm")
public class GreedyAlgorithm implements SchedulingAlgorithm {
    @Override
    public String getName() {
        return "greedy";
    }

    @Override
    public UAVNode selectNode(List<UAVNode> availableNodes, TaskInfo task) {
        return availableNodes.stream()
                .max(Comparator.comparingDouble(UAVNode::getAvailableCpu))
                .orElse(null);
    }
}
