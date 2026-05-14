package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 自定义权重调度算法
 *
 * 策略：使用任务级自定义权重综合评分选择节点
 *
 * 评分公式：score = (W2 * CPU可用率) + (W3 * 电池率) - (W1 * 距离率)
 * - W1: 距离权重（来自 task.customW1）
 * - W2: CPU权重（来自 task.customW2）
 * - W3: 电池权重（来自 task.customW3）
 *
 * 适用场景：需要用户自定义调度偏好的场景
 * 注意：权重通过任务提交时指定
 */
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
