package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

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
