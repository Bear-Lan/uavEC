package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component("customAlgorithm")
public class CustomAlgorithm implements SchedulingAlgorithm {
    @Override
    public String getName() {
        return "custom";
    }

    @Override
    public UAVNode selectNode(List<UAVNode> availableNodes, TaskInfo task) {
        double maxDist = Math.sqrt(100 * 100 + 100 * 100);
        return availableNodes.stream()
                .max(Comparator.comparingDouble(n -> {
                    double cpuRatio = n.getAvailableCpu() / n.getMaxCpu();
                    double batRatio = n.getBattery() / 100.0;
                    double dist = calculateDistance(n.getX(), n.getY(), task.getOriginX(), task.getOriginY());
                    double distRatio = dist / maxDist;

                    double w1 = task.getCustomW2();
                    double w2 = task.getCustomW3();
                    double w3 = task.getCustomW1();

                    double score = (w1 * cpuRatio) + (w2 * batRatio) - (w3 * distRatio);
                    if (n.getBattery() < 10.0)
                        score -= 100.0;
                    return score;
                }))
                .orElse(null);
    }
}
