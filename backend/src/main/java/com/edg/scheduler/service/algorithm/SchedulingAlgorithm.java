package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import java.util.List;

public interface SchedulingAlgorithm {
    String getName();

    UAVNode selectNode(List<UAVNode> availableNodes, TaskInfo task);

    default double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }
}
