package com.edg.scheduler.service.offloading.strategy;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.service.offloading.model.CloudStatus;
import com.edg.scheduler.service.offloading.model.OffloadResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 能耗最优卸载策略
 *
 * 基于 DVFS（Dynamic Voltage and Frequency Scaling）思想的能耗优化决策：
 * - 本地计算能耗: E_edge = kappa * f^2 * w * dataSize
 * - 传输能耗: E_trans = Pt * (dataSize / bandwidth)
 * - 选择能耗较小的执行路径
 *
 * DVFS 模型说明：
 * - 处理器功耗与工作频率的平方成正比
 * - kappa: 芯片常数（取决于工艺）
 * - f: 工作频率（与 CPU 算力相关）
 * - w: 任务 workload（数据量）
 */
@Slf4j
@Component("energyOptimalStrategy")
public class EnergyOptimalStrategy implements OffloadingStrategy {

    // DVFS 模型参数
    private static final double KAPPA = 0.5;           // 芯片能耗常数
    private static final double BASE_FREQUENCY = 2.0;  // 基准频率 (GHz)
    private static final double TX_POWER_WATTS = 2.0;  // 传输功耗 (W)
    private static final double UPLINK_BANDWIDTH_MBPS = 50.0;

    @Override
    public OffloadResult calculateOffloadingPath(UAVNode node, TaskInfo task, CloudStatus cloud) {
        // 1. 计算本地执行能耗（DVFS 模型）
        double localEnergyJ = calculateLocalEnergy(task, node);

        // 2. 计算传输能耗
        double transEnergyJ = calculateTransEnergy(task);

        // 3. 考虑云端计算能耗（如果传输到云端）
        double cloudComputeEnergyJ = calculateCloudComputeEnergy(task, cloud);

        // 4. 计算总能耗对比
        double edgeTotalEnergy = localEnergyJ;
        double cloudTotalEnergy = transEnergyJ + cloudComputeEnergyJ;

        // 5. 考虑电池状态 - 电量低时更倾向于云端
        double batteryFactor = calculateBatteryFactor(node.getBattery());

        log.info("任务 {} [能耗最优] 本地: {}J vs 云端: {}J (电池因子: {:.2f})",
                task.getTaskName(),
                formatEnergy(localEnergyJ),
                formatEnergy(cloudTotalEnergy),
                batteryFactor);

        // 综合考虑能耗和电池状态
        if (edgeTotalEnergy * batteryFactor < cloudTotalEnergy) {
            return OffloadResult.edge(
                    String.format("边缘能耗%.1fJ < 云端能耗%.1fJ（电池%d%%）",
                            localEnergyJ, cloudTotalEnergy, (int) node.getBattery()),
                    estimateEdgeLatency(task, node),
                    localEnergyJ);
        } else {
            return OffloadResult.cloud(
                    String.format("云端能耗%.1fJ < 边缘能耗%.1fJ（电池%d%%）",
                            cloudTotalEnergy, localEnergyJ, (int) node.getBattery()),
                    estimateCloudLatency(task, cloud),
                    cloudTotalEnergy);
        }
    }

    @Override
    public String getName() {
        return "energy";
    }

    /**
     * 计算本地执行能耗
     * E_edge = kappa * f^2 * w * dataSize
     *
     * @param task 任务信息
     * @param node 边缘节点
     * @return 本地计算能耗（焦耳）
     */
    private double calculateLocalEnergy(TaskInfo task, UAVNode node) {
        double f = node.getMaxCpu() / BASE_FREQUENCY;  // 归一化频率
        double w = task.getDataSize();                  // workload (MB)

        // E = kappa * f^2 * w (处理w数据量需要的能耗)
        double energy = KAPPA * Math.pow(f, 2) * w;
        return energy;
    }

    /**
     * 计算传输能耗
     * E_trans = Pt * (dataSize / bandwidth)
     *
     * @param task 任务信息
     * @return 传输能耗（焦耳）
     */
    private double calculateTransEnergy(TaskInfo task) {
        double txTimeSec = task.getDataSize() / UPLINK_BANDWIDTH_MBPS;
        return TX_POWER_WATTS * txTimeSec;
    }

    /**
     * 计算云端计算能耗
     * 云端假设使用高效服务器，能耗较低
     *
     * @param task 任务信息
     * @param cloud 云端状态
     * @return 云端计算能耗（焦耳）
     */
    private double calculateCloudComputeEnergy(TaskInfo task, CloudStatus cloud) {
        // 云端服务器假设使用高效的 7nm 工艺，能耗仅为边缘的 10%
        double computePowerWatts = 0.5; // 0.5W per core（高效服务器）
        double computeTimeSec = (task.getDataSize() / cloud.getAvailableCpu()) * 0.1;
        return computePowerWatts * computeTimeSec;
    }

    /**
     * 计算电池影响因子
     * 电量越低，越倾向于选择能耗低的方案（即使延迟更高）
     *
     * @param battery 电量百分比 (0-100)
     * @return 影响因子（电量高时为1，电量低时 > 1）
     */
    private double calculateBatteryFactor(double battery) {
        if (battery >= 80) {
            return 1.0;
        } else if (battery >= 50) {
            return 1.2;
        } else if (battery >= 20) {
            return 1.5;
        } else {
            return 2.0; // 电量低于20%，强烈倾向节能
        }
    }

    /**
     * 估算边缘执行延迟
     */
    private double estimateEdgeLatency(TaskInfo task, UAVNode node) {
        double computeTimeSec = (task.getDataSize() / node.getMaxCpu()) * 0.1;
        double distance = calculateDistance(node.getX(), node.getY(), task.getOriginX(), task.getOriginY());
        double txDelaySec = distance * 0.005;
        return (computeTimeSec + txDelaySec) * 1000;
    }

    /**
     * 估算云端执行延迟
     */
    private double estimateCloudLatency(TaskInfo task, CloudStatus cloud) {
        double uplinkSec = task.getDataSize() / UPLINK_BANDWIDTH_MBPS;
        double computeSec = (task.getDataSize() / cloud.getAvailableCpu()) * 0.1;
        try {
            double queueWaitSec = cloud.calculateQueueWaitTime();
            return (uplinkSec + queueWaitSec + computeSec + 0.05) * 1000;
        } catch (IllegalStateException e) {
            return (uplinkSec + computeSec + 0.05) * 1000;
        }
    }

    /**
     * 计算两点欧几里得距离
     */
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    /**
     * 格式化能耗输出，整数不带小数点
     */
    private String formatEnergy(double joules) {
        if (joules == Math.floor(joules) && !Double.isInfinite(joules)) {
            return String.format("%.0f", joules);
        }
        return String.format("%.2f", joules);
    }
}