package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 加权公平队列调度算法
 *
 * 策略：选择当前活跃任务数量最少的节点
 * 优点：负载均衡，避免单节点过载
 * 缺点：可能选择负载低但资源有限的节点
 */
@Component("wfqAlgorithm")
public class WfqAlgorithm implements SchedulingAlgorithm {
    @Override
    public String getName() {
        return "wfq";
    }

    @Override
    public UAVNode selectNode(List<UAVNode> availableNodes, TaskInfo task) {
        return availableNodes.stream()
                .min(Comparator.comparingInt(n -> n.getActiveTasksCount().get()))
                .orElse(null);
    }
}
