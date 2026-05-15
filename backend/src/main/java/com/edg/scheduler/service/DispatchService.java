package com.edg.scheduler.service;

import com.edg.scheduler.model.DispatchResult;
import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

import com.edg.scheduler.service.algorithm.SchedulingAlgorithm;
import com.edg.scheduler.model.TaskTraceLog;
import com.edg.scheduler.repository.TaskTraceLogRepository;

import org.redisson.api.RedissonClient;

/**
 * 任务分发服务
 *
 * 核心职责：
 * - 根据调度算法选择最优节点
 * - 卸载决策（threshold / energy / latency）
 * - 部分卸载支持（大数据任务分割处理）
 * - 云端降级处理
 * - 实时状态广播（WebSocket）
 *
 * 优化特性：
 * - Shannon 容量带宽模型
 * - 历史性能预测
 * - 能耗感知决策
 * - 延迟优化决策
 */
@Slf4j
@Service
public class DispatchService {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskTraceLogRepository traceLogRepository;

    private final Map<String, SchedulingAlgorithm> algorithms;

    /**
     * 构造函数 - 初始化调度算法映射
     *
     * 功能描述：
     * - 注入所有实现了SchedulingAlgorithm接口的算法组件
     * - 将算法名称作为key，算法实例作为value存入Map
     * - 用于后续快速查找调度算法
     *
     * @param algoList Spring自动注入的算法列表
     */
    @Autowired
    public DispatchService(List<SchedulingAlgorithm> algoList) {
        this.algorithms = algoList.stream()
                .collect(Collectors.toMap(SchedulingAlgorithm::getName, algo -> algo));
    }

    @Value("${app.scheduler.algorithm:greedy}")
    private String algorithm;

    @Value("${app.scheduler.offload.partial-threshold-mb:200.0}")
    private double partialThresholdMb;

    @Value("${app.scheduler.offload.partial-edge-ratio:0.3}")
    private double partialEdgeRatio;

    @Value("${app.scheduler.offload.cloud-cpu-cores:64.0}")
    private double cloudCpuCores;

    @Value("${app.scheduler.offload.cloud-bandwidth-mbps:50.0}")
    private double cloudBandwidthMbps;

    @Value("${app.scheduler.offload.wan-latency-ms:50.0}")
    private double wanLatencyMs;

    @Value("${app.scheduler.offload.cloud-queue-penalty-ms:2000.0}")
    private double cloudQueuePenaltyMs;

    @Value("${app.scheduler.offload.distance-delay-ms-per-unit:5.0}")
    private double distanceDelayMsPerUnit;

    /**
     * 分发任务到最优节点
     *
     * 功能描述：
     * - 根据任务调度算法选择最优节点
     * - 执行卸载决策（threshold/energy/latency）
     * - 支持部分卸载（大任务分割处理）
     * - 支持云端降级处理
     * - 异步执行任务并记录追踪日志
     *
     * 决策流程：
     * 1. 过滤可用节点（在线且资源充足）
     * 2. 使用调度算法选择最优节点
     * 3. 根据offloadAlgorithm决定是否卸载到云端
     * 4. 部分卸载时分割任务到边缘和云端
     * 5. 云端降级作为最终fallback
     *
     * @param task 待分发的任务
     * @return 分发结果（成功/失败、分配节点、任务信息）
     */
    @Transactional
    public DispatchResult dispatch(TaskInfo task) {
        // ---------- 1. 确定调度算法 ----------
        // 优先使用任务自己设定的调度算法，否则使用全局配置的默认算法
        String effectiveAlgorithm = (task.getSchedulingAlgorithm() != null && !task.getSchedulingAlgorithm().isBlank())
                ? task.getSchedulingAlgorithm()
                : algorithm;

        // ---------- 2. 过滤可用节点 ----------
        // 必须同时满足：节点在线、CPU充足、内存充足
        List<UAVNode> availableNodes = nodeService.getAllNodes().stream()
                .filter(UAVNode::isOnline)
                .filter(n -> n.getMaxCpu() >= task.getRequiredCpu()
                        && n.getMaxMemory() >= task.getRequiredMemory())
                .toList();

        // 无可用节点时，直接降级到云端执行
        if (availableNodes.isEmpty()) {
            log.warn("任务 {} 所需资源(CPU:{}, 内存:{})超过所有无人机上限，切换到云端执行",
                    task.getTaskName(), task.getRequiredCpu(), task.getRequiredMemory());
            return executeCloudFallback(task, "任务所需资源超过所有无人机上限");
        }

        // ---------- 3. 选择最优节点 ----------
        SchedulingAlgorithm algo = algorithms.get(effectiveAlgorithm.toLowerCase());
        UAVNode selectedNode;

        if (algo != null) {
            // 根据调度算法（greedy/wfq/geo/custom）选择最优节点
            selectedNode = algo.selectNode(availableNodes, task);
        } else {
            // 算法未找到时，默认选择第一个可用节点
            selectedNode = availableNodes.get(0);
        }

        if (selectedNode == null) {
            return DispatchResult.fail(task, "未找到合适的无人机节点");
        }

        // ---------- 4. 卸载决策逻辑 ----------
        // 决定任务是在边缘节点执行还是卸载到云端
        boolean offloadToCloud = false;
        // 卸载算法优先级：任务设定 > 默认latency
        String offloadAlg = task.getOffloadAlgorithm() != null ? task.getOffloadAlgorithm() : "latency";

        // 计算任务原点到无人机的欧几里得距离
        double distance = calculateDistance(selectedNode.getX(), selectedNode.getY(), task.getOriginX(),
                task.getOriginY());

        // ---- Shannon信道容量模型计算有效带宽 ----
        // 有效带宽 = 基带带宽 × log2(1+SNR)，SNR随距离平方衰减
        double baseBandwidthMBps = selectedNode.getNetworkBandwidth() / 8.0;
        double pathLossMultiplier = Math.pow(Math.max(1.0, distance / 10.0), 2);
        double snr = 100.0 / pathLossMultiplier; // 近距离基准SNR=100
        double shannonFactor = Math.log(1 + snr) / Math.log(2); // log2(1+SNR)
        double maxFactor = Math.log(1 + 100.0) / Math.log(2);
        double effectiveBandwidthMBps = baseBandwidthMBps * (shannonFactor / maxFactor);
        effectiveBandwidthMBps = Math.max(0.5, effectiveBandwidthMBps); // 最小带宽兜底

        // ---- 历史预测：根据历史执行日志预测本地执行时间 ----
        double localTimeStrPredict = (task.getDataSize() / selectedNode.getMaxCpu()) * 0.1; // 默认估算
        List<TaskTraceLog> historyLogs = traceLogRepository
                .findTop10ByTaskNameOrderByExecutionEndTimeDesc(task.getTaskName());

        double avgHistoricalComputeMs = 0;
        int validLogs = 0;
        for (TaskTraceLog logEntry : historyLogs) {
            // 只使用有效的边缘节点执行日志
            if (logEntry.getComputeLatency() > 0
                    && logEntry.getAssignedUavId() != null && logEntry.getAssignedUavId().startsWith("UAV")) {
                avgHistoricalComputeMs += logEntry.getComputeLatency();
                validLogs++;
            }
        }

        if (validLogs > 0) {
            // 有历史数据时，使用历史平均值预测
            double avgMs = avgHistoricalComputeMs / validLogs;
            localTimeStrPredict = avgMs / 1000.0;
            log.info("任务 {}: 使用历史预测本地执行时间: {}秒", task.getTaskName(), String.format("%.3f", localTimeStrPredict));
        }

        // ---- 根据卸载算法做决策 ----
        if ("energy".equalsIgnoreCase(offloadAlg)) {
            // 能耗感知策略：比较本地计算能耗 vs 传输能耗
            double localPower = 5.0; // 本地计算功率 (W)
            double localEnergy = localPower * localTimeStrPredict;

            double baseTxPower = 2.0; // 传输功率 (W)
            double txPower = baseTxPower * pathLossMultiplier;
            double txTime = task.getDataSize() / effectiveBandwidthMBps;
            double offloadEnergy = txPower * txTime;

            log.info("任务 {}: 能耗评估 - 本地:{}J vs 传输:{}J (距离:{}, 衰减:{})",
                    task.getTaskName(), String.format("%.2f", localEnergy), String.format("%.2f", offloadEnergy),
                    String.format("%.2f", distance), String.format("%.2f", pathLossMultiplier));

            // 本地能耗过高或电池不足时，卸载到云端
            if (localEnergy > offloadEnergy * 5.0 || selectedNode.getBattery() < 20.0) {
                offloadToCloud = true;
            }
        } else if ("latency".equalsIgnoreCase(offloadAlg)) {
            // 延迟最优策略：比较本地执行时间 vs 云端总耗时
            // 云端总耗时 = 传输时间 + 云端计算时间 + 广域网延迟 + 排队惩罚
            double txTime = task.getDataSize() / effectiveBandwidthMBps; // 传输到云端耗时
            double cloudExecutionTime = (task.getDataSize() / cloudCpuCores) * 0.1; // 云端计算耗时
            double totalOffloadTime = txTime + cloudExecutionTime + (wanLatencyMs / 1000.0) + (cloudQueuePenaltyMs / 1000.0);

            log.info("任务 {}: 延迟评估 - 本地(预测):{}秒 vs 云端:{}秒 (有效带宽:{}MB/s)",
                    task.getTaskName(), String.format("%.3f", localTimeStrPredict), String.format("%.3f", totalOffloadTime),
                    String.format("%.1f", effectiveBandwidthMBps));

            if (totalOffloadTime < localTimeStrPredict) {
                offloadToCloud = true;
            }
        } else {
            // 阈值策略：数据量超过500MB时强制卸载到云端
            offloadToCloud = task.getDataSize() > 500;
        }

        // ---------- 5. 分割卸载（数据量超过阈值时） ----------
        // 大数据量任务（>200MB）采用分割执行：边缘处理30%，云端处理70%
        boolean partialOffload = task.getDataSize() > partialThresholdMb;

        if (partialOffload) {
            log.info("任务 {} 触发分割卸载(数据量:{}MB > 阈值:{}MB)，分割比例:边缘{}% 云端{}%",
                    task.getTaskName(), task.getDataSize(), partialThresholdMb,
                    (int)(partialEdgeRatio * 100), (int)((1 - partialEdgeRatio) * 100));

            if (selectedNode != null
                    && nodeService.allocate(selectedNode.getId(), task.getRequiredCpu(), task.getRequiredMemory())) {
                task.setAssignedUavId(selectedNode.getId() + " & CLOUD");
                task.setStatus("RUNNING_SPLIT");
                task.setStartTime(System.currentTimeMillis());

                // 数据分割
                double localData = task.getDataSize() * partialEdgeRatio;
                double cloudData = task.getDataSize() * (1 - partialEdgeRatio);

                // 边缘处理时间 = 本地计算 + 传输延迟
                long localExecutionMs = (long) ((localData / selectedNode.getMaxCpu()) * 100);
                long transmissionDelay = (long) (distance * distanceDelayMsPerUnit);
                long totalEdgeTime = localExecutionMs + transmissionDelay;

                // 云端处理时间 = 传输时间 + 计算时间 + 广域网延迟
                long cloudTxMs = (long) ((cloudData / cloudBandwidthMbps) * 1000);
                long cloudComputeMs = (long) ((cloudData / cloudCpuCores) * 100);
                long wanLatencyDelay = (long) wanLatencyMs;
                long totalCloudTime = cloudTxMs + cloudComputeMs + wanLatencyDelay;

                // 任务耗时取边缘和云端的最大值（并行执行）
                long bottleneckDelay = Math.max(totalEdgeTime, totalCloudTime);

                // 更新追踪日志
                TaskTraceLog traceLog = traceLogRepository.findByTaskId(task.getId());
                if (traceLog != null) {
                    traceLog.setAssignedUavId(selectedNode.getId() + " & CLOUD");
                    traceLog.setTaskName(task.getTaskName());
                    traceLog.setExecutionStartTime(System.currentTimeMillis() + transmissionDelay);
                    traceLog.setTxLatency(transmissionDelay);
                    traceLogRepository.save(traceLog);
                }

                broadcastState();
                broadcastTaskUpdate(task);

                final String assignNodeId = selectedNode.getId();

                // 异步执行分割任务
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(bottleneckDelay);
                        task.setStatus("COMPLETED");
                        task.setEndTime(System.currentTimeMillis());

                        // 能耗 = 本地计算能耗 + 云端传输能耗
                        double computingPower = 5.0;
                        double localEnergy = computingPower * (localExecutionMs / 1000.0);
                        double baseTxPower = 2.0;
                        double txPower = baseTxPower * pathLossMultiplier;
                        double cloudTxEnergy = txPower * (cloudTxMs / 1000.0);
                        task.setActualEnergyUsed(localEnergy + cloudTxEnergy);

                        // 更新追踪日志
                        if (traceLog != null) {
                            long now = System.currentTimeMillis();
                            traceLog.setExecutionEndTime(now);
                            traceLog.setComputeLatency(now - traceLog.getExecutionStartTime());
                            traceLogRepository.save(traceLog);
                        }
                    } catch (InterruptedException e) {
                        task.setStatus("FAILED");
                        Thread.currentThread().interrupt();
                    } finally {
                        taskRepository.save(task);
                        redissonClient.getMap("task:active").remove(task.getId());
                        nodeService.release(assignNodeId, task.getRequiredCpu(), task.getRequiredMemory());
                        broadcastState();
                        broadcastTaskUpdate(task);
                    }
                });
                return DispatchResult.success(task, task.getAssignedUavId());
            } else {
                return executeCloudFallback(task, "分割卸载时边缘节点资源分配失败");
            }
        } else if (offloadToCloud) {
            // ---------- 6. 完全卸载到云端 ----------
            log.info("任务 {} 根据 '{}' 算法决策卸载到云端(经过节点{})",
                    task.getTaskName(), offloadAlg, selectedNode.getId());
            task.setAssignedUavId("CLOUD-SERVER");
            task.setStatus("RUNNING_CLOUD");
            task.setStartTime(System.currentTimeMillis());

            // 传输延迟 = 数据传输时间 + 广域网延迟
            long transmissionDelay = (long) ((task.getDataSize() / cloudBandwidthMbps) * 1000) + (long) wanLatencyMs;

            // 更新追踪日志
            TaskTraceLog traceLog = traceLogRepository.findByTaskId(task.getId());
            if (traceLog != null) {
                traceLog.setAssignedUavId("CLOUD-SERVER");
                traceLog.setTaskName(task.getTaskName());
                traceLog.setExecutionStartTime(System.currentTimeMillis() + transmissionDelay);
                traceLog.setTxLatency(transmissionDelay);
                traceLogRepository.save(traceLog);
            }

            broadcastState();
            broadcastTaskUpdate(task);

            // 云端异步执行（不占用无人机资源）
            CompletableFuture.runAsync(() -> {
                try {
                    long cloudExecutionMs = (long) ((task.getDataSize() / cloudCpuCores) * 100);
                    Thread.sleep(Math.max(1000, cloudExecutionMs) + transmissionDelay);
                    task.setStatus("COMPLETED");
                    task.setEndTime(System.currentTimeMillis());

                    // 云端执行：无人机仅计算传输能耗
                    double txPower = 2.0;
                    double txTime = task.getDataSize() / cloudBandwidthMbps;
                    task.setActualEnergyUsed(txPower * txTime);

                    // 更新追踪日志
                    if (traceLog != null) {
                        long now = System.currentTimeMillis();
                        traceLog.setExecutionEndTime(now);
                        traceLog.setComputeLatency(now - traceLog.getExecutionStartTime());
                        traceLogRepository.save(traceLog);
                    }
                } catch (InterruptedException e) {
                    task.setStatus("FAILED");
                } finally {
                    taskRepository.save(task);
                    redissonClient.getMap("task:active").remove(task.getId());
                    broadcastState();
                    broadcastTaskUpdate(task);
                }
            });
            return DispatchResult.success(task, "CLOUD-SERVER");
        }

        // ---------- 7. 边缘节点执行（默认） ----------
        if (selectedNode != null
                && nodeService.allocate(selectedNode.getId(), task.getRequiredCpu(), task.getRequiredMemory())) {
            task.setAssignedUavId(selectedNode.getId());
            task.setStatus("RUNNING_EDGE");
            task.setStartTime(System.currentTimeMillis());

            // 注册到活跃任务表
            redissonClient.getMap("task:active").put(task.getId(), task);

            log.info("任务 {} 使用 {} 算法分发到节点 {}", task.getTaskName(), effectiveAlgorithm, selectedNode.getId());

            // 根据欧式距离计算传输延迟
            distance = calculateDistance(selectedNode.getX(), selectedNode.getY(), task.getOriginX(),
                    task.getOriginY());
            long transmissionDelay = (long) (distance * distanceDelayMsPerUnit);
            log.info("任务 {} 传输延迟: {}毫秒 (距离:{}, 每单位延迟:{}ms)",
                    task.getTaskName(), transmissionDelay, String.format("%.2f", distance), distanceDelayMsPerUnit);

            TaskTraceLog traceLog = traceLogRepository.findByTaskId(task.getId());
            if (traceLog != null) {
                traceLog.setAssignedUavId(selectedNode.getId());
                traceLog.setTaskName(task.getTaskName());
                traceLog.setExecutionStartTime(System.currentTimeMillis() + transmissionDelay);
                traceLog.setTxLatency(transmissionDelay);
                traceLogRepository.save(traceLog);
            }

            // 广播状态更新
            broadcastState();
            broadcastTaskUpdate(task);

            // 异步模拟边缘执行
            simulateExecution(task, selectedNode, transmissionDelay);

            return DispatchResult.success(task, selectedNode.getId());
        }

        // ---------- 8. 降级到云端（边缘节点分配失败） ----------
        log.warn("任务 {} 所有边缘节点分配失败，降级到云端执行", task.getTaskName());
        return executeCloudFallback(task, "边缘节点资源分配失败");
    }

    /**
     * 云端降级处理
     *
     * 功能描述：
     * - 当边缘节点分配失败时执行
     * - 将任务分配到CLOUD-SERVER虚拟节点
     * - 计算传输延迟（数据量/带宽+广域网延迟）
     * - 异步执行云端计算
     * - 记录追踪日志
     *
     * @param task 任务对象
     * @param reason 降级原因
     * @return 分发结果
     */
    private DispatchResult executeCloudFallback(TaskInfo task, String reason) {
        log.info("Task {} falling back to CLOUD-SERVER. Reason: {}", task.getId(), reason);
        task.setAssignedUavId("CLOUD-SERVER");
        task.setStatus("RUNNING_CLOUD");
        task.setStartTime(System.currentTimeMillis());

        long fallbackTxDelay = (long) ((task.getDataSize() / cloudBandwidthMbps) * 1000) + (long) wanLatencyMs; // 毫秒 (广域网 + 协议开销)

        TaskTraceLog fallbackTrace = traceLogRepository.findByTaskId(task.getId());
        if (fallbackTrace != null) {
            fallbackTrace.setAssignedUavId("CLOUD-SERVER");
            fallbackTrace.setTaskName(task.getTaskName());
            fallbackTrace.setExecutionStartTime(System.currentTimeMillis() + fallbackTxDelay);
            fallbackTrace.setTxLatency(fallbackTxDelay);
            traceLogRepository.save(fallbackTrace);
        }

        broadcastState();
        broadcastTaskUpdate(task); // 广播任务 DISPATCHED 状态

        CompletableFuture.runAsync(() -> {
            try {
                long cloudExecMs = (long) ((task.getDataSize() / cloudCpuCores) * 100);
                Thread.sleep(Math.max(1500, cloudExecMs) + fallbackTxDelay);
                task.setStatus("COMPLETED");
                task.setEndTime(System.currentTimeMillis());
                double txPower = 2.0;
                double txTime = task.getDataSize() / cloudBandwidthMbps;
                task.setActualEnergyUsed(txPower * txTime);

                if (fallbackTrace != null) {
                    long now = System.currentTimeMillis();
                    fallbackTrace.setExecutionEndTime(now);
                    fallbackTrace.setComputeLatency(now - fallbackTrace.getExecutionStartTime());
                    traceLogRepository.save(fallbackTrace);
                }
            } catch (InterruptedException e) {
                task.setStatus("FAILED");
            } finally {
                taskRepository.save(task);
                redissonClient.getMap("task:active").remove(task.getId());
                broadcastState();
                broadcastTaskUpdate(task);
            }
        });

        return DispatchResult.success(task, "CLOUD-SERVER (Fallback)");
    }

    /**
     * 模拟任务执行过程
     *
     * 功能描述：
     * - 异步执行任务模拟
     * - 根据数据量和节点CPU计算执行时间
     * - 执行完成后更新任务状态为COMPLETED
     * - 使用状态栅栏防止覆盖已迁移的任务
     * - 计算并记录实际能耗
     * - 任务完成后释放节点资源
     *
     * 状态栅栏机制：
     * - 执行前检查数据库中任务状态
     * - 如果任务已被迁移或重排，则中止执行
     * - 防止"幽灵进程"覆盖有效状态
     *
     * @param task 任务对象
     * @param node 执行节点
     * @param transmissionDelay 传输延迟（毫秒）
     */
    private void simulateExecution(TaskInfo task, UAVNode node, long transmissionDelay) {
        CompletableFuture.runAsync(() -> {
            try {
                // 根据载荷大小和节点主频预估执行时间 (Determine execution time based on task size and node CPU)
                // 原型假设: 1 MB 数据量 / 1 核心 CPU 算力 = ~100ms
                long executionTimeMs = (long) ((task.getDataSize() / node.getMaxCpu()) * 100);

                // 叠加人为阻塞延时 + 物理发送传输延时
                executionTimeMs = Math.max(2000, Math.min(10000, executionTimeMs * 10)) + transmissionDelay;

                Thread.sleep(executionTimeMs);

                // --- 重要: 状态栅栏 (Status Gate) ---
                // 在完成模拟睡眠后，核对数据库中该任务的状态。
                // 如果任务已被迁移 (MIGRATED) 或 因节点坠毁而重排 (QUEUED)，则此"幽灵进程"必须终止，不得篡改状态。
                TaskInfo latestTaskCheck = taskRepository.findById(task.getId()).orElse(null);
                if (latestTaskCheck == null || !"RUNNING_EDGE".equals(latestTaskCheck.getStatus())) {
                    log.warn("Simulation for task {} aborted. Task status in DB is '{}', current thread is stale.",
                            task.getId(), (latestTaskCheck != null ? latestTaskCheck.getStatus() : "DELETED"));
                    return;
                }

                task.setStatus("COMPLETED");
                task.setEndTime(System.currentTimeMillis());

                // 阶段 6 遥感数据: 真机电池物理电量扣除核算模型
                double computingPower = 5.0; // 假设满载活动时为 5 瓦特
                double timeInSec = (System.currentTimeMillis() - task.getStartTime()) / 1000.0;
                task.setActualEnergyUsed(computingPower * timeInSec);

                TaskTraceLog traceLog = traceLogRepository.findByTaskId(task.getId());
                if (traceLog != null) {
                    long now = System.currentTimeMillis();
                    traceLog.setExecutionEndTime(now);
                    traceLog.setComputeLatency(now - traceLog.getExecutionStartTime());
                    traceLogRepository.save(traceLog);
                }

                log.info("Task {} completed on Node {} (Energy: {} J)", task.getId(), node.getId(),
                        String.format("%.2f", task.getActualEnergyUsed()));

            } catch (InterruptedException e) {
                task.setStatus("FAILED");

                // 沉没电量成本 (Sunk energy cost) — 在排队被中止、或只算了一半的情况下扣除死角电量
                // 包含部分计算+待机空转
                double sunkPower = 3.0; // 瓦特 (Watts)
                double sunkTimeSec = (System.currentTimeMillis() - task.getStartTime()) / 1000.0;
                task.setActualEnergyUsed(sunkPower * sunkTimeSec);

                TaskTraceLog traceLog = traceLogRepository.findByTaskId(task.getId());
                if (traceLog != null) {
                    long now = System.currentTimeMillis();
                    traceLog.setExecutionEndTime(now);
                    traceLog.setComputeLatency(now - traceLog.getExecutionStartTime());
                    traceLogRepository.save(traceLog);
                }

                log.error("Task {} FAILED on Node {} (Sunk Energy: {} J)", task.getId(), node.getId(),
                        String.format("%.2f", task.getActualEnergyUsed()), e);
            } finally {
                // 向 MySQL 落盘更新特定任务数据状态
                TaskInfo finalCheck = taskRepository.findById(task.getId()).orElse(task);

                // 仅当任务仍处于"运行/分发"类状态时才进行归檔；防止覆盖已"迁移/重排"的最新状态
                if (finalCheck.getStatus() != null && finalCheck.getStatus().startsWith("RUNNING")) {
                    taskRepository.save(task);
                }

                // 从活跃字典中移除
                redissonClient.getMap("task:active").remove(task.getId());

                // 重新派生查询，以防该任务在进行至中途时，已经被其他饥饿的可用节点"窃取"接盘 (Work-Stealing)!
                String actualNodeId = finalCheck.getAssignedUavId();
                if (actualNodeId == null || !actualNodeId.startsWith("UAV")) {
                    actualNodeId = node.getId(); // 如果发生诡异覆盖则降级回初始登记节点
                }

                nodeService.release(actualNodeId, task.getRequiredCpu(), task.getRequiredMemory());
                broadcastState();
                broadcastTaskUpdate(task);
            }
        });
    }

    /**
     * 广播节点状态到WebSocket
     *
     * 功能描述：
     * - 获取所有节点的当前状态
     * - 推送到/topic/nodes主题
     * - 供前端实时更新节点列表
     */
    public void broadcastState() {
        messagingTemplate.convertAndSend("/topic/nodes", nodeService.getAllNodes());
    }

    /**
     * 广播任务状态更新到WebSocket
     *
     * @param task 任务对象
     */
    private void broadcastTaskUpdate(TaskInfo task) {
        messagingTemplate.convertAndSend("/topic/tasks", task);
    }

    /**
     * 计算两点之间的欧几里得距离
     *
     * @param x1 点1 X坐标
     * @param y1 点1 Y坐标
     * @param x2 点2 X坐标
     * @param y2 点2 Y坐标
     * @return 欧几里得距离
     */
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }
}
