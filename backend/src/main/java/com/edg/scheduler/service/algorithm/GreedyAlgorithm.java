package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

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
