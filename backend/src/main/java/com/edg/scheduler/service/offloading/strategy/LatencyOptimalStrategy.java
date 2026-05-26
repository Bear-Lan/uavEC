package com.edg.scheduler.service.offloading.strategy;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.service.offloading.model.CloudStatus;
import com.edg.scheduler.service.offloading.model.OffloadResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 延迟最优卸载策略
 *
 * 基于 M/M/1 排队论的延迟最优决策：
 * - 计算云端排队等待时间: W = 1 / (mu - lambda)
 * - 计算云端总耗时: 传输时间 + 排队等待 + 计算时间 + 结果回传
 * - 与边缘执行时间比较，选择延迟更小的路径
 *
 * M/M/1 模型假设：
 * - 任务到达服从泊松分布（到达率 lambda）
 * - 服务时间服从指数分布（服务率 mu）
 * - 单服务器队列
 */
@Slf4j
@Component("latencyOptimalStrategy")
public class LatencyOptimalStrategy implements OffloadingStrategy {

    private static final double UPLINK_BANDWIDTH_MBPS = 50.0;   // 上行带宽 MB/s（基准值，用于Shannon归一化）
    private static final double DOWNLINK_BANDWIDTH_MBPS = 50.0; // 下行带宽 MB/s
    private static final double RESULT_SIZE_MB = 1.0;           // 结果数据大小 MB

    @Override
    public OffloadResult calculateOffloadingPath(UAVNode node, TaskInfo task, CloudStatus cloud) {
        // 1. 计算边缘执行时间
        double edgeExecTimeSec = calculateEdgeExecutionTime(task, node);

        // 2. 计算云端执行时间（M/M/1 排队论 + Shannon 动态带宽）
        double cloudTotalTimeSec;
        try {
            cloudTotalTimeSec = calculateCloudTotalTime(task, node, cloud);
        } catch (IllegalStateException e) {
            // 云端拥塞，返回边缘执行
            log.warn("任务 {} 云端拥塞，强制边缘执行: {}", task.getTaskName(), e.getMessage());
            return OffloadResult.edge("云端拥塞-" + e.getMessage(), edgeExecTimeSec * 1000, 0);
        }

        // 3. 比较延迟
        double edgeLatencyMs = edgeExecTimeSec * 1000;
        double cloudLatencyMs = cloudTotalTimeSec * 1000;

        log.info("任务 {} [延迟最优] 边缘耗时: {}ms vs 云端耗时: {}ms",
                task.getTaskName(),
                formatMs(edgeLatencyMs),
                formatMs(cloudLatencyMs));

        if (cloudTotalTimeSec < edgeExecTimeSec * 0.8) {
            return OffloadResult.cloud(
                    String.format("云端延迟%.2fms < 边缘延迟%.2fms × 0.8", cloudLatencyMs, edgeLatencyMs),
                    cloudLatencyMs,
                    estimateCloudEnergy(task, cloudTotalTimeSec));
        } else {
            return OffloadResult.edge(
                    String.format("边缘延迟%.2fms <= 云端延迟%.2fms", edgeLatencyMs, cloudLatencyMs),
                    edgeLatencyMs,
                    estimateEdgeEnergy(task, node, edgeExecTimeSec));
        }
    }

    @Override
    public String getName() {
        return "latency";
    }

    /**
     * 计算边缘执行时间
     * 包括：计算时间 + 传输延迟
     */
    private double calculateEdgeExecutionTime(TaskInfo task, UAVNode node) {
        double dataSizeMB = task.getDataSize();
        double cpuCores = node.getMaxCpu();

        // 计算时间: dataSize / cpuCores * 0.1秒（假设每核处理1MB需要0.1秒）
        double computeTimeSec = (dataSizeMB / cpuCores) * 0.1;

        // 传输延迟（任务原点到节点的无线传输）
        double distance = calculateDistance(node.getX(), node.getY(), task.getOriginX(), task.getOriginY());
        double transmissionDelaySec = distance * 0.005; // 每单位距离5ms

        return computeTimeSec + transmissionDelaySec;
    }

    /**
     * 计算云端总时间
     * = 上传时间 + M/M/1排队等待 + 云端计算时间 + 结果下载时间
     * 其中上传时间使用 Shannon 容量模型计算动态有效带宽
     */
    private double calculateCloudTotalTime(TaskInfo task, UAVNode node, CloudStatus cloud) {
        double dataSizeMB = task.getDataSize();
        double resultSizeMB = RESULT_SIZE_MB;

        // 1. 上传时间（基于 Shannon 动态带宽）
        double effectiveBandwidth = calculateShannonBandwidth(node, task, cloud);
        double uplinkTimeSec = dataSizeMB / effectiveBandwidth;

        // 2. M/M/1 排队等待时间
        double queueWaitSec;
        try {
            queueWaitSec = cloud.calculateQueueWaitTime();
        } catch (IllegalStateException e) {
            throw e; // 重新抛出拥塞异常
        }

        // 3. 云端计算时间
        double cloudComputeSec = (dataSizeMB / cloud.getAvailableCpu()) * 0.1;

        // 4. 结果下载时间（固定带宽）
        double downlinkTimeSec = resultSizeMB / DOWNLINK_BANDWIDTH_MBPS;

        // 5. 广域网延迟（往返）
        double wanLatencySec = 0.05; // 50ms RTT

        return uplinkTimeSec + queueWaitSec + cloudComputeSec + downlinkTimeSec + wanLatencySec;
    }

    /**
     * 基于 Shannon 容量模型计算边缘到云端的有效传输带宽
     * C = B × log2(1 + SNR)
     * 其中 SNR = 100 / pathLossMultiplier，pathLossMultiplier = (distance / 10)^2
     * 距离 = 无人机节点到任务原点的距离（无线链路质量决定有效带宽）
     *
     * @param node 无人机节点（发射端）
     * @param task 任务信息（包含原点坐标）
     * @param cloud 云端状态（用于获取基准带宽）
     * @return 有效传输带宽（MB/s）
     */
    private double calculateShannonBandwidth(UAVNode node, TaskInfo task, CloudStatus cloud) {
        // 基准带宽：使用云端配置的带宽
        double baseBandwidthMBps = cloud.getBandwidthMbps() / 8.0;  // Mbps → MB/s
        if (baseBandwidthMBps <= 0) baseBandwidthMBps = UPLINK_BANDWIDTH_MBPS;
        // 距离：无人机节点到任务原点的距离（决定路径损耗）
        double distance = calculateDistance(node.getX(), node.getY(), task.getOriginX(), task.getOriginY());
        double pathLossMultiplier = Math.pow(Math.max(1.0, distance / 10.0), 2);
        double snr = 100.0 / pathLossMultiplier;
        // Shannon: C = B × log2(1+SNR)，归一化到基准SNR=100时等于baseBandwidth
        double effectiveBandwidth = baseBandwidthMBps * Math.log(1 + snr) / Math.log(1 + 100.0);
        return Math.max(0.5, effectiveBandwidth);
    }

    /**
     * 估算边缘能耗
     */
    private double estimateEdgeEnergy(TaskInfo task, UAVNode node, double execTimeSec) {
        double computePower = 5.0; // 5W 计算功率
        double drainMultiplier = node.getDrainRateMultiplier();
        return computePower * drainMultiplier * execTimeSec;
    }

    /**
     * 估算云端能耗（传输功耗）
     */
    private double estimateCloudEnergy(TaskInfo task, double execTimeSec) {
        double txPower = 2.0; // 2W 传输功率
        double txTimeSec = task.getDataSize() / UPLINK_BANDWIDTH_MBPS;
        return txPower * txTimeSec;
    }

    /**
     * 计算两点欧几里得距离
     */
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    /**
     * 格式化毫秒输出，整数不带小数点
     */
    private String formatMs(double ms) {
        if (ms == Math.floor(ms) && !Double.isInfinite(ms)) {
            return String.format("%.0f", ms);
        }
        return String.format("%.2f", ms);
    }
}