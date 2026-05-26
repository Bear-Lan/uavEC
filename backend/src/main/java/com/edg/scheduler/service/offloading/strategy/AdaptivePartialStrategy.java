package com.edg.scheduler.service.offloading.strategy;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.service.offloading.model.CloudStatus;
import com.edg.scheduler.service.offloading.model.OffloadResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自适应部分卸载策略
 *
 * 动态计算边缘保留比例，不再使用死板的阈值：
 * - rho = V_edge / (V_edge + V_trans)
 * - V_edge: 边缘计算价值（速度优势）
 * - V_trans: 传输价值（节省的边缘计算量）
 *
 * 计算步骤：
 * 1. 计算边缘计算时间
 * 2. 计算传输时间
 * 3. 动态计算分割比例
 * 4. 返回部分卸载结果
 */
@Slf4j
@Component("adaptivePartialStrategy")
public class AdaptivePartialStrategy implements OffloadingStrategy {

    private static final double UPLINK_BANDWIDTH_MBPS = 50.0;
    private static final double CLOUD_CPU_CORES = 64.0;
    private static final double WAN_LATENCY_MS = 50.0;
    private static final double CLOUD_QUEUE_PENALTY_MS = 2000.0;

    @Override
    public OffloadResult calculateOffloadingPath(UAVNode node, TaskInfo task, CloudStatus cloud) {
        double dataSizeMB = task.getDataSize();

        // 1. 计算边缘执行时间
        double edgeComputeTimeSec = calculateEdgeComputeTime(task, node);
        double distance = calculateDistance(node.getX(), node.getY(), task.getOriginX(), task.getOriginY());
        double edgeTxDelaySec = distance * 0.005;
        double totalEdgeTimeSec = edgeComputeTimeSec + edgeTxDelaySec;

        // 2. 计算云端执行时间（基于 Shannon 动态带宽）
        double effectiveBandwidth = calculateShannonBandwidth(node, task, cloud);
        double cloudUplinkSec = dataSizeMB / effectiveBandwidth;
        double cloudComputeSec = (dataSizeMB / CLOUD_CPU_CORES) * 0.1;
        double queueWaitSec = 0;
        try {
            queueWaitSec = cloud.calculateQueueWaitTime();
        } catch (IllegalStateException e) {
            queueWaitSec = CLOUD_QUEUE_PENALTY_MS / 1000.0;
        }
        double wanLatencySec = WAN_LATENCY_MS / 1000.0;
        double totalCloudTimeSec = cloudUplinkSec + queueWaitSec + cloudComputeSec + wanLatencySec;

        // 3. 动态计算边缘保留比例 rho
        // V_edge = 边缘计算时间 / 数据量（秒/MB）- 表示处理每MB数据的时间
        // V_trans = 传输时间 / 数据量（秒/MB）- 表示传输每MB数据的时间
        // rho = V_edge / (V_edge + V_trans)
        // 如果 V_edge < V_trans，说明边缘比传输快，应该保留更多在边缘
        double V_edge = totalEdgeTimeSec / dataSizeMB;
        double V_trans = cloudUplinkSec / dataSizeMB;
        double rho;
        if (V_edge + V_trans == 0) {
            rho = 0.3; // 默认值
        } else {
            rho = V_edge / (V_edge + V_trans);
        }

        // 限制 rho 在 [0.1, 0.9] 范围内，避免极端情况
        rho = Math.max(0.1, Math.min(0.9, rho));
        double cloudRatio = 1.0 - rho;

        // 6. 计算预估延迟（取边缘和云端的最大值，因为是并行执行）
        double edgeTimeForPartial = edgeComputeTimeSec * rho + edgeTxDelaySec;
        double cloudTimeForPartial = cloudUplinkSec * cloudRatio + cloudComputeSec * cloudRatio + queueWaitSec + wanLatencySec;
        double bottleneckTimeSec = Math.max(edgeTimeForPartial, cloudTimeForPartial);

        // 7. 决策：小任务直接边缘，大任务自适应部分卸载
        if (dataSizeMB < 50) {
            // 小任务直接边缘执行
            return OffloadResult.edge(
                    String.format("小任务(%sMB)直接边缘执行", formatInt(dataSizeMB)),
                    totalEdgeTimeSec * 1000,
                    estimateEdgeEnergy(task, node, totalEdgeTimeSec));
        } else if (totalCloudTimeSec > totalEdgeTimeSec * 1.5) {
            // 云端太慢，边缘执行
            return OffloadResult.edge(
                    String.format("云端%.2fms > 边缘%.2fms × 1.5，选择边缘",
                            totalCloudTimeSec * 1000, totalEdgeTimeSec * 1000),
                    totalEdgeTimeSec * 1000,
                    estimateEdgeEnergy(task, node, totalEdgeTimeSec));
        } else {
            // 自适应部分卸载
            log.info("任务 {} [自适应部分卸载] 数据量:{}MB, rho:{:.2f}, 边缘:{:.0f}% vs 云端:{:.0f}%, 瓶颈耗时:{}{}",
                    task.getTaskName(),
                    formatInt(dataSizeMB),
                    rho,
                    formatPercent(rho),
                    formatPercent(cloudRatio),
                    formatMs(bottleneckTimeSec * 1000));

            return OffloadResult.partial(
                    String.format("自适应分割: 边缘%.0f%% + 云端%.0f%%（rho=%.2f）",
                            rho * 100, cloudRatio * 100, rho),
                    rho,
                    cloudRatio,
                    bottleneckTimeSec * 1000,
                    estimatePartialEnergy(task, node, cloud, rho));
        }
    }

    @Override
    public String getName() {
        return "adaptive";
    }

    /**
     * 计算边缘计算时间
     */
    private double calculateEdgeComputeTime(TaskInfo task, UAVNode node) {
        return (task.getDataSize() / node.getMaxCpu()) * 0.1;
    }

    /**
     * 估算边缘能耗
     */
    private double estimateEdgeEnergy(TaskInfo task, UAVNode node, double execTimeSec) {
        double computePower = 5.0;
        double drainMultiplier = node.getDrainRateMultiplier();
        return computePower * drainMultiplier * execTimeSec;
    }

    /**
     * 估算部分卸载总能耗
     */
    private double estimatePartialEnergy(TaskInfo task, UAVNode node, CloudStatus cloud, double rho) {
        double dataSizeMB = task.getDataSize();
        double cloudRatio = 1.0 - rho;
        double edgeDataMB = dataSizeMB * rho;
        double cloudDataMB = dataSizeMB * cloudRatio;

        // 边缘计算能耗
        double edgeComputeTimeSec = (edgeDataMB / node.getMaxCpu()) * 0.1;
        double edgeEnergy = 5.0 * node.getDrainRateMultiplier() * edgeComputeTimeSec;

        // 传输能耗（基于 Shannon 动态带宽）
        double effectiveBandwidth = calculateShannonBandwidth(node, task, cloud);
        double txTimeSec = cloudDataMB / effectiveBandwidth;
        double pathLoss = calculatePathLoss(node, task);
        double txEnergy = 2.0 * pathLoss * txTimeSec;

        // 云端计算能耗
        double cloudComputeTimeSec = (cloudDataMB / cloud.getAvailableCpu()) * 0.1;
        double cloudEnergy = 0.5 * cloudComputeTimeSec;

        return edgeEnergy + txEnergy + cloudEnergy;
    }

    /**
     * 基于 Shannon 容量模型计算有效传输带宽
     * C = B × log2(1 + SNR)
     */
    private double calculateShannonBandwidth(UAVNode node, TaskInfo task, CloudStatus cloud) {
        double baseBandwidthMBps = cloud.getBandwidthMbps() / 8.0;
        if (baseBandwidthMBps <= 0) baseBandwidthMBps = UPLINK_BANDWIDTH_MBPS;
        double pathLoss = calculatePathLoss(node, task);
        double snr = 100.0 / pathLoss;
        double effectiveBandwidth = baseBandwidthMBps * Math.log(1 + snr) / Math.log(1 + 100.0);
        return Math.max(0.5, effectiveBandwidth);
    }

    /**
     * 计算路径损耗因子
     */
    private double calculatePathLoss(UAVNode node, TaskInfo task) {
        double distance = calculateDistance(node.getX(), node.getY(), task.getOriginX(), task.getOriginY());
        return Math.pow(Math.max(1.0, distance / 10.0), 2);
    }

    /**
     * 计算两点欧几里得距离
     */
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    /**
     * 格式化整数输出（整数不带小数点）
     */
    private String formatInt(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.format("%.0f", value);
        }
        return String.format("%.2f", value);
    }

    /**
     * 格式化百分比输出
     */
    private String formatPercent(double value) {
        return String.format("%.0f", value * 100);
    }

    /**
     * 格式化毫秒输出
     */
    private String formatMs(double ms) {
        if (ms == Math.floor(ms) && !Double.isInfinite(ms)) {
            return String.format("%.0fms", ms);
        }
        return String.format("%.2fms", ms);
    }
}