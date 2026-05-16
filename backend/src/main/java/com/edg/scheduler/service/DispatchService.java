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

import com.edg.scheduler.service.offloading.CloudStatus;
import com.edg.scheduler.service.offloading.OffloadResult;
import com.edg.scheduler.service.offloading.OffloadingStrategy;

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

    @Autowired
    private UAVSimulationService uavSimulationService;

    private final Map<String, SchedulingAlgorithm> algorithms;
    private final Map<String, OffloadingStrategy> offloadingStrategies;

    /**
     * 构造函数 - 初始化调度算法映射和卸载策略映射
     *
     * 功能描述：
     * - 注入所有实现了SchedulingAlgorithm接口的算法组件
     * - 注入所有实现了OffloadingStrategy接口的卸载策略组件
     * - 将算法名称作为key，算法实例作为value存入Map
     * - 用于后续快速查找调度算法和卸载策略
     *
     * @param algoList Spring自动注入的算法列表
     * @param offloadList Spring自动注入的卸载策略列表
     */
    @Autowired
    public DispatchService(List<SchedulingAlgorithm> algoList, List<OffloadingStrategy> offloadList) {
        this.algorithms = algoList.stream()
                .collect(Collectors.toMap(SchedulingAlgorithm::getName, algo -> algo));
        this.offloadingStrategies = offloadList.stream()
                .collect(Collectors.toMap(OffloadingStrategy::getName, strategy -> strategy));
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

    @Value("${app.scheduler.simulation.uav-thread-enabled:true}")
    private boolean uavThreadEnabled;

    /**
     * 任务分发入口
     *
     * 执行流程（6步）：
     * 1. 过滤可用节点（在线、资源充足、电量充足）
     * 2. 若无可用节点 → 直接云端降级
     * 3. 获取卸载策略（latency/energy/adaptive/dqn）
     * 4. 使用卸载策略评估 → 决策 CLOUD / PARTIAL / EDGE
     * 5. 若为 CLOUD → 云端执行（无需调度算法）
     * 6. 若为 PARTIAL/EDGE → 用调度算法选节点，再执行
     *
     * 设计原则：卸载策略决定 "是否要边缘执行"，调度算法决定 "用哪个边缘节点"
     * - 云端执行：不需要调度算法
     * - 边缘执行：需要调度算法选择最优节点
     *
     * @param task 待分发的任务
     * @return 分发结果
     */
    @Transactional
    public DispatchResult dispatch(TaskInfo task) {
        // ========== Step 1: 过滤可用节点 ==========
        // 条件：在线 + 资源充足 + 电量 > 20%
        List<UAVNode> availableNodes = nodeService.getAllNodes().stream()
                .filter(UAVNode::isOnline)
                .filter(n -> n.getMaxCpu() >= task.getRequiredCpu()
                        && n.getMaxMemory() >= task.getRequiredMemory())
                .filter(n -> n.getBattery() >= 20.0)
                .toList();

        // ========== Step 2: 无可用节点 → 云端降级 ==========
        if (availableNodes.isEmpty()) {
            log.warn("任务 {} 无可用边缘节点，切换到云端执行", task.getTaskName());
            return executeCloudFallback(task, "无可用边缘节点");
        }

        // ========== Step 3: 获取卸载策略 ==========
        String offloadAlg = task.getOffloadAlgorithm() != null ? task.getOffloadAlgorithm() : "latency";
        OffloadingStrategy strategy = offloadingStrategies.get(offloadAlg.toLowerCase());
        if (strategy == null) {
            log.warn("未找到卸载策略 {}，使用默认latency策略", offloadAlg);
            strategy = offloadingStrategies.get("latency");
        }

        // ========== Step 4: 使用卸载策略评估 ==========
        // 选择代表性节点用于策略评估（距离任务原点最近的节点）
        UAVNode representativeNode = availableNodes.stream()
                .min((a, b) -> {
                    double distA = calculateDistance(a.getX(), a.getY(), task.getOriginX(), task.getOriginY());
                    double distB = calculateDistance(b.getX(), b.getY(), task.getOriginX(), task.getOriginY());
                    return Double.compare(distA, distB);
                })
                .orElse(availableNodes.get(0));

        CloudStatus cloudStatus = buildCloudStatus();
        OffloadResult offloadResult = strategy.calculateOffloadingPath(representativeNode, task, cloudStatus);

        log.info("任务 {} 卸载决策: {} | 原因: {} | 候选节点: {}",
                task.getTaskName(), offloadResult.getDecision(), offloadResult.getReason(), representativeNode.getId());

        // ========== Step 5: CLOUD → 云端执行（跳过调度算法） ==========
        if (offloadResult.getDecision() == OffloadResult.Decision.CLOUD) {
            return executeCloudFallback(task, offloadResult.getReason());
        }

        // ========== Step 6: PARTIAL / EDGE → 调度算法选节点 ==========
        // 只有需要边缘执行时，调度算法才有意义
        String effectiveAlgorithm = (task.getSchedulingAlgorithm() != null && !task.getSchedulingAlgorithm().isBlank())
                ? task.getSchedulingAlgorithm()
                : algorithm;

        SchedulingAlgorithm algo = algorithms.get(effectiveAlgorithm.toLowerCase());
        UAVNode selectedNode = (algo != null)
                ? algo.selectNode(availableNodes, task)
                : availableNodes.get(0);

        if (selectedNode == null) {
            return DispatchResult.fail(task, "未找到合适的无人机节点");
        }

        // 根据决策执行
        if (offloadResult.getDecision() == OffloadResult.Decision.PARTIAL) {
            // PARTIAL: 分割卸载（边缘 + 云端）
            return dispatchPartialOffload(task, selectedNode,
                    offloadResult.getEdgeRatio(), offloadResult.getCloudRatio());
        } else {
            // EDGE: 边缘执行
            return dispatchToEdge(task, selectedNode);
        }
    }

    /**
     * 分割卸载分发
     * 任务数据分割为两部分：边缘处理 + 云端处理
     *
     * @param task 任务信息
     * @param selectedNode 已选中的边缘执行节点
     * @param edgeRatio 边缘处理比例（如 0.3 表示边缘处理 30%）
     * @param cloudRatio 云端处理比例（如 0.7 表示云端处理 70%）
     */
    private DispatchResult dispatchPartialOffload(TaskInfo task, UAVNode selectedNode,
                                                  double edgeRatio, double cloudRatio) {

        if (selectedNode == null || !nodeService.allocate(selectedNode.getId(), task.getRequiredCpu(), task.getRequiredMemory())) {
            return executeCloudFallback(task, "分割卸载节点分配失败");
        }

        log.info("任务 {} 触发分割卸载，数据量:{}MB，边缘:{:.0f}% 云端:{:.0f}%",
                task.getTaskName(), formatInt(task.getDataSize()),
                edgeRatio * 100, cloudRatio * 100);

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
        double localData = task.getDataSize() * edgeRatio;
        double cloudData = task.getDataSize() * cloudRatio;

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

        // 根据配置选择使用 UAV 独立线程执行或后端线程池模拟
        if (uavThreadEnabled) {
            // 投递任务到 UAV worker 线程执行
            boolean enqueued = uavSimulationService.enqueueTask(selectedNode.getId(), task);
            if (!enqueued) {
                log.warn("UAV {} task queue full, falling back to async simulation", selectedNode.getId());
                simulateExecution(task, selectedNode, transmissionDelay);
            }
        } else {
            simulateExecution(task, selectedNode, transmissionDelay);
        }

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
     * 构建云端状态信息
     */
    private CloudStatus buildCloudStatus() {
        CloudStatus status = new CloudStatus();
        status.setLambda(0.5);  // 默认到达率 0.5 任务/秒
        status.setMu(1.0);      // 默认服务率 1.0 任务/秒
        status.setAvailableCpu(cloudCpuCores);
        status.setBandwidthMbps(cloudBandwidthMbps);

        // 可以从 Redisson 或数据库获取实际的队列长度等信息
        Object queueLen = redissonClient.getMap("cloud:stats").get("queueLength");
        if (queueLen != null) {
            status.setQueueLength((Integer) queueLen);
            // 估算实际到达率 = 队列长度 / 观察窗口
            status.setLambda(Math.min(5.0, status.getQueueLength() * 0.1));
        }

        return status;
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
