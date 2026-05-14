package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 地理拓扑调度算法
 *
 * 策略：综合考虑CPU可用率、电池剩余、地理距离进行评分选择
 * 评分公式：score = (0.3 * CPU可用率) + (0.2 * 电池率) - (0.5 * 距离率)
 * - CPU权重：0.3
 * - 电池权重：0.2
 * - 距离权重：0.5（距离越远分数越低）
 * 低电量节点（<10%）会被大幅降分
 * 适用场景：需要考虑无人机物理位置和电池续航的场景
 */
@Component("geoAlgorithm")
public class GeoAlgorithm implements SchedulingAlgorithm {
    @Override
    public String getName() {
        return "geo";
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

                    double w1 = 0.3;
                    double w2 = 0.2;
                    double w3 = 0.5;

                    double score = (w1 * cpuRatio) + (w2 * batRatio) - (w3 * distRatio);
                    if (n.getBattery() < 10.0)
                        score -= 100.0;
                    return score;
                }))
                .orElse(null);
    }
}
