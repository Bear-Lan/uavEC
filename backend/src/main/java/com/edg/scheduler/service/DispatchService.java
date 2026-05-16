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
        String effectiveAlgorithm = (task.getSchedulingAlgorithm() != null && !task.getSchedulingAlgorithm().isBlank())
                ? task.getSchedulingAlgorithm()
                : algorithm;

        // ---------- 2. 先做卸载决策（不需要具体节点，用平均估算） ----------
        // 卸载决策优先级：任务设定 > 默认latency
        String offloadAlg = task.getOffloadAlgorithm() != null ? task.getOffloadAlgorithm() : "latency";

        // 用平均估算距离和带宽，用于卸载决策
        double avgDistance = 50.0; // 假设平均距离50单位
        double pathLossMultiplier = Math.pow(Math.max(1.0, avgDistance / 10.0), 2);
        double snr = 100.0 / pathLossMultiplier;
        double shannonFactor = Math.log(1 + snr) / Math.log(2);
        double maxFactor = Math.log(1 + 100.0) / Math.log(2);
        double avgBandwidthMBps = Math.max(0.5, (100.0 / 8.0) * (shannonFactor / maxFactor)); // 归一化带宽估算

        // 估算本地执行时间（假设平均CPU为4核）
        double localTimeEstimate = (task.getDataSize() / 4.0) * 0.1; // 秒

        // 根据卸载算法做决策
        boolean offloadToCloud = false;

        if ("energy".equalsIgnoreCase(offloadAlg)) {
            // 能耗感知策略：比较本地计算能耗 vs 传输能耗
            double localPower = 5.0; // 本地计算功率 (W)
            double localEnergy = localPower * localTimeEstimate;

            double baseTxPower = 2.0; // 传输功率 (W)
            double txPower = baseTxPower * pathLossMultiplier;
            double txTime = task.getDataSize() / avgBandwidthMBps;
            double offloadEnergy = txPower * txTime;

            log.info("任务 {}: 能耗评估 - 本地:{}J vs 传输:{}J (平均距离:{}, 衰减:{})",
                    task.getTaskName(), String.format("%.2f", localEnergy), String.format("%.2f", offloadEnergy),
                    String.format("%.2f", avgDistance), String.format("%.2f", pathLossMultiplier));

            // 本地能耗过高时卸载到云端（简化判断，不考虑具体节点电量）
            if (localEnergy > offloadEnergy * 3.0) {
                offloadToCloud = true;
            }
        } else if ("latency".equalsIgnoreCase(offloadAlg)) {
            // 延迟最优策略：比较本地执行时间 vs 云端总耗时
            double txTime = task.getDataSize() / avgBandwidthMBps; // 估算传输到云端耗时
            double cloudExecutionTime = (task.getDataSize() / cloudCpuCores) * 0.1;
            double totalOffloadTime = txTime + cloudExecutionTime + (wanLatencyMs / 1000.0) + (cloudQueuePenaltyMs / 1000.0);

            log.info("任务 {}: 延迟评估 - 本地(估算):{}秒 vs 云端:{}秒",
                    task.getTaskName(), String.format("%.3f", localTimeEstimate), String.format("%.3f", totalOffloadTime));

            if (totalOffloadTime < localTimeEstimate * 0.8) { // 乘以0.8的阈值，避免频繁切换
                offloadToCloud = true;
            }
        } else {
            // 阈值策略：数据量超过500MB时强制卸载到云端
            offloadToCloud = task.getDataSize() > 500;
        }

        // ---------- 3. 如果决定云端执行，直接走云端 ----------
        if (offloadToCloud) {
            log.info("任务 {} 决策卸载到云端执行", task.getTaskName());
            return executeCloudFallback(task, "延迟/能耗策略决策云端执行");
        }

        // ---------- 4. 边缘执行：过滤可用节点 ----------
        List<UAVNode> availableNodes = nodeService.getAllNodes().stream()
                .filter(UAVNode::isOnline)
                .filter(n -> n.getMaxCpu() >= task.getRequiredCpu()
                        && n.getMaxMemory() >= task.getRequiredMemory())
                // 电池电量过低不分配新任务
                .filter(n -> n.getBattery() >= 20.0)
                .toList();

        if (availableNodes.isEmpty()) {
            log.warn("任务 {} 无可用边缘节点，切换到云端执行", task.getTaskName());
            return executeCloudFallback(task, "无可用边缘节点");
        }

        // ---------- 5. 分割卸载判断 ----------
        boolean partialOffload = task.getDataSize() > partialThresholdMb;
        if (partialOffload) {
            return dispatchPartialOffload(task, availableNodes, effectiveAlgorithm);
        }

        // ---------- 6. 选择最优节点 ----------
        SchedulingAlgorithm algo = algorithms.get(effectiveAlgorithm.toLowerCase());
        UAVNode selectedNode;
        if (algo != null) {
            selectedNode = algo.selectNode(availableNodes, task);
        } else {
            selectedNode = availableNodes.get(0);
        }

        if (selectedNode == null) {
            return DispatchResult.fail(task, "未找到合适的无人机节点");
        }

        // ---------- 7. 边缘节点执行 ----------
        return dispatchToEdge(task, selectedNode);
    }

    /**
     * 分割卸载分发
     * 大数据量任务（>200MB）采用分割执行：边缘处理30%，云端处理70%
     */
    private DispatchResult dispatchPartialOffload(TaskInfo task, List<UAVNode> availableNodes, String effectiveAlgorithm) {
        // 选择一个最优节点参与分割卸载
        SchedulingAlgorithm algo = algorithms.get(effectiveAlgorithm.toLowerCase());
        UAVNode selectedNode = algo != null ? algo.selectNode(availableNodes, task) : availableNodes.get(0);

        if (selectedNode == null || !nodeService.allocate(selectedNode.getId(), task.getRequiredCpu(), task.getRequiredMemory())) {
            return executeCloudFallback(task, "分割卸载节点分配失败");
        }

        log.info("任务 {} 触发分割卸载(数据量:{}MB > 阈值:{}MB)，分割比例:边缘{}% 云端{}%",
                task.getTaskName(), task.getDataSize(), partialThresholdMb,
                (int)(partialEdgeRatio * 100), (int)((1 - partialEdgeRatio) * 100));

        task.setAssignedUavId(selectedNode.getId() + " & CLOUD");
        task.setStatus("RUNNING_SPLIT");
        task.setStartTime(System.currentTimeMillis());

        // 计算距离和带宽
        double distance = calculateDistance(selectedNode.getX(), selectedNode.getY(), task.getOriginX(), task.getOriginY());
        double pathLossMultiplier = Math.pow(Math.max(1.0, distance / 10.0), 2);
        double baseBandwidthMBps = selectedNode.getNetworkBandwidth() / 8.0;
        double snr = 100.0 / pathLossMultiplier;
        double effectiveBandwidthMBps = baseBandwidthMBps * Math.log(1 + snr) / Math.log(2) / Math.log(1 + 100.0);
        effectiveBandwidthMBps = Math.max(0.5, effectiveBandwidthMBps);

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
        long totalCloudTime = cloudTxMs + cloudComputeMs + (long) wanLatencyMs;

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
                double localEnergy = 5.0 * (localExecutionMs / 1000.0);
                double txPower = 2.0 * pathLossMultiplier;
                double cloudTxEnergy = txPower * (cloudTxMs / 1000.0);
                task.setActualEnergyUsed(localEnergy + cloudTxEnergy);

                if (traceLog != null) {
                    traceLog.setExecutionEndTime(System.currentTimeMillis());
                    traceLog.setComputeLatency(bottleneckDelay);
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
    }

    /**
     * 边缘节点执行分发
     */
    private DispatchResult dispatchToEdge(TaskInfo task, UAVNode selectedNode) {
        if (!nodeService.allocate(selectedNode.getId(), task.getRequiredCpu(), task.getRequiredMemory())) {
            log.warn("任务 {} 节点 {} 资源分配失败，降级到云端", task.getTaskName(), selectedNode.getId());
            return executeCloudFallback(task, "边缘节点资源分配失败");
        }

        task.setAssignedUavId(selectedNode.getId());
        task.setStatus("RUNNING_EDGE");
        task.setStartTime(System.currentTimeMillis());

        redissonClient.getMap("task:active").put(task.getId(), task);

        log.info("任务 {} 使用调度算法分发到节点 {}", task.getTaskName(), selectedNode.getId());

        // 计算传输延迟
        double distance = calculateDistance(selectedNode.getX(), selectedNode.getY(), task.getOriginX(), task.getOriginY());
        long transmissionDelay = (long) (distance * distanceDelayMsPerUnit);

        TaskTraceLog traceLog = traceLogRepository.findByTaskId(task.getId());
        if (traceLog != null) {
            traceLog.setAssignedUavId(selectedNode.getId());
            traceLog.setTaskName(task.getTaskName());
            traceLog.setExecutionStartTime(System.currentTimeMillis() + transmissionDelay);
            traceLog.setTxLatency(transmissionDelay);
            traceLogRepository.save(traceLog);
        }

        broadcastState();
        broadcastTaskUpdate(task);

        // 异步模拟边缘执行
        simulateExecution(task, selectedNode, transmissionDelay);

        return DispatchResult.success(task, selectedNode.getId());
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
