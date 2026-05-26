package com.edg.scheduler.service;

import com.edg.scheduler.model.UAVNode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Lazy;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无人机节点服务层
 *
 * 核心职责：
 * - 节点生命周期管理（初始化、添加、删除）
 * - 资源分配与释放（基于 Redisson 分布式锁）
 * - 环境模拟（电池消耗、航点巡航、光伏充能）
 * - 故障检测与自动恢复（RTH 返航、坠毁处理）
 * - 集群快照与回滚（用于混沌工程测试）
 *
 * 硬件异构支持：
 * - HEAVY_GPU: 高算力节点
 * - BALANCED: 均衡型节点（默认）
 * - SOLAR_SCOUT: 光伏充能节点
 * - MICRO_SENSOR: 微型传感器节点
 */
@Slf4j
@Service
public class NodeService {

    @Value("${app.scheduler.node-count:3}")
    private int initialNodeCount;

    private AtomicInteger idCounter = new AtomicInteger(0);

    @Value("${app.scheduler.simulation.base-drain-rate:0.2}")
    private double baseDrainRate;

    @Value("${app.scheduler.simulation.compute-drain-multiplier:0.8}")
    private double computeDrainMultiplier;

    @Value("${app.scheduler.simulation.move-speed:2.0}")
    private double moveSpeed;

    @Value("${app.scheduler.simulation.rth-speed:3.0}")
    private double rthSpeed;

    @Value("${app.scheduler.simulation.rth-threshold:15.0}")
    private double rthThreshold;

    @Value("${app.scheduler.simulation.charge-rate:10.0}")
    private double chargeRate;

    private final Map<String, UAVNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, UAVNode> snapshotNodes = new ConcurrentHashMap<>();

    // 地理路由巡航坐标点 — 每一个节点将会在这些航点间循环盘旋
    private static final double[][] WAYPOINTS = {
            { 15, 20 }, { 80, 15 }, { 75, 80 }, { 20, 75 }, { 50, 50 },
            { 30, 40 }, { 65, 30 }, { 85, 60 }, { 40, 85 }, { 10, 55 }
    };

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    @Lazy
    private TaskService taskService;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 应用硬件配置文件到节点
     *
     * 功能描述：
     * - 根据节点索引选择硬件配置文件
     * - 支持4种硬件类型：HEAVY_GPU、SOLAR_SCOUT、MICRO_SENSOR、BALANCED
     * - 设置对应的CPU、内存、带宽、能耗参数
     *
     * 硬件配置详情：
     * - HEAVY_GPU: 双倍CPU和内存，200Mbps带宽，能耗2.5倍
     * - SOLAR_SCOUT: 半价CPU，50Mbps带宽，能耗0.3倍，光伏充能0.6%/2秒
     * - MICRO_SENSOR: 1/4 CPU，20Mbps带宽，能耗0.15倍
     * - BALANCED: 默认配置，无特殊属性
     *
     * @param node 目标节点
     * @param nodeIndex 节点索引（用于选择配置）
     */
    private void applyHardwareProfile(UAVNode node, int nodeIndex) {
        int profileSelector = nodeIndex % 4;
        switch (profileSelector) {
            case 0 -> { // HEAVY_GPU: High compute, high drain
                node.setHardwareProfile("HEAVY_GPU");
                node.setMaxCpu(node.getMaxCpu() * 2); // Double CPU
                node.setMaxMemory(node.getMaxMemory() * 2); // Double RAM
                node.setNetworkBandwidth(200.0);
                node.setDrainRateMultiplier(2.5); // Drains 2.5x faster
                node.setSolarHarvesting(false);
                node.setSolarChargeRate(0.0);
                node.setName(node.getName() + " [GPU]");
            }
            case 1 -> { // SOLAR_SCOUT: Low compute, slow drain, solar recharge
                node.setHardwareProfile("SOLAR_SCOUT");
                node.setMaxCpu(node.getMaxCpu() * 0.5);
                node.setNetworkBandwidth(50.0);
                node.setDrainRateMultiplier(0.3); // Drains very slowly
                node.setSolarHarvesting(true);
                node.setSolarChargeRate(0.6); // +0.6% per tick (~2s)
                node.setName(node.getName() + " [SOLAR]");
            }
            case 2 -> { // MICRO_SENSOR: Minimal compute node
                node.setHardwareProfile("MICRO_SENSOR");
                node.setMaxCpu(node.getMaxCpu() * 0.25);
                node.setNetworkBandwidth(20.0);
                node.setDrainRateMultiplier(0.15);
                node.setSolarHarvesting(false);
                node.setSolarChargeRate(0.0);
                node.setName(node.getName() + " [SENSOR]");
            }
            default -> { // BALANCED: Default profile
                node.setHardwareProfile("BALANCED");
                node.setDrainRateMultiplier(1.0);
                node.setSolarHarvesting(false);
                node.setSolarChargeRate(0.0);
            }
        }
    }

    /**
     * 初始化节点集群
     *
     * 功能描述：
     * - 在Spring容器初始化完成后（@PostConstruct）自动调用
     * - 根据配置创建initialNodeCount个无人机节点
     * - 节点ID格式：UAV-1, UAV-2, ...
     * - 交替设置CPU：偶数ID为8核，奇数ID为4核
     * - 为每个节点分配航点坐标和硬件配置文件
     * - 所有节点初始状态为在线
     */
    @PostConstruct
    public void initNodes() {
        for (int i = 1; i <= initialNodeCount; i++) {
            int nextId = idCounter.incrementAndGet();
            String id = "UAV-" + nextId;
            double maxCpu = (nextId % 2 == 0) ? 8.0 : 4.0;
            UAVNode node = new UAVNode(id, "UAV Node " + nextId, maxCpu);
            int wpIndex = (nextId - 1) % WAYPOINTS.length;
            node.setX(WAYPOINTS[wpIndex][0]);
            node.setY(WAYPOINTS[wpIndex][1]);
            applyHardwareProfile(node, nextId);
            nodes.put(id, node);
        }
        log.info("已初始化 {} 个异构无人机节点", initialNodeCount);
    }

    /**
     * 动态添加新节点
     *
     * 功能描述：
     * - 使用Redisson分布式锁保证线程安全
     * - 生成新的节点ID（格式：UAV-N，N递增）
     * - 根据ID奇偶性设置CPU核心数
     * - 应用硬件配置文件
     * - 添加到节点映射并推送更新到WebSocket
     *
     * @return 新创建的节点对象
     */
    @Transactional
    public UAVNode addNode() {
        RLock lock = redissonClient.getLock("node:management");
        try {
            lock.lock(5, TimeUnit.SECONDS);
            int nextId = idCounter.incrementAndGet();
            String id = "UAV-" + nextId;
            double maxCpu = (nextId % 2 == 0) ? 8.0 : 4.0;
            UAVNode newNode = new UAVNode(id, "UAV Node " + nextId, maxCpu);
            applyHardwareProfile(newNode, nextId);
            nodes.put(id, newNode);
            log.info("动态新增无人机节点: {} (配置: {})", id, newNode.getHardwareProfile());
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
            return newNode;
        } finally {
            if (lock.isHeldByCurrentThread())
                lock.unlock();
        }
    }

    /**
     * 删除指定节点
     *
     * 功能描述：
     * - 使用Redisson分布式锁保证线程安全
     * - 从节点映射中移除指定节点
     * - 调用TaskService重新排队该节点的所有活跃任务
     * - 推送节点列表更新到WebSocket
     *
     * @param nodeId 要删除的节点ID
     * @return 是否删除成功
     */
    public boolean deleteNode(String nodeId) {
        RLock lock = redissonClient.getLock("node:management");
        try {
            lock.lock(5, TimeUnit.SECONDS);
            if (nodes.containsKey(nodeId)) {
                nodes.remove(nodeId);
                log.info("动态删除无人机节点: {}", nodeId);
                taskService.requeueActiveTasksForNode(nodeId);
                messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
                return true;
            }
            return false;
        } finally {
            if (lock.isHeldByCurrentThread())
                lock.unlock();
        }
    }

    /**
     * 紧急充电 - 手动恢复节点电量
     *
     * 功能描述：
     * - 将指定节点的电池充至100%
     * - 设置节点状态为在线
     * - 退出RTH返航模式和充电状态
     * - 解除手动位置锁定，恢复自动巡逻
     * - 用于故障恢复后的紧急处理
     *
     * @param nodeId 目标节点ID
     */
    public void emergencyCharge(String nodeId) {
        UAVNode node = nodes.get(nodeId);
        if (node != null) {
            node.setBattery(100.0);
            node.setOnline(true);
            node.setRthMode(false);
            node.setCharging(false);
            node.setManualOverride(false); // 解除锚定锁定，恢复自动巡逻
            log.info("紧急充电: {} 电池已充至 100%", nodeId);
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
        }
    }

    // --- Sandbox Isolation Mechanism ---

    /**
     * 创建集群快照
     *
     * 功能描述：
     * - 使用Redisson分布式锁保证线程安全
     * - 深拷贝所有当前节点状态到snapshotNodes映射
     * - 用于混沌工程测试，支持回滚到快照状态
     *
     * 快照内容：
     * - 所有节点的完整状态（位置、电量、资源使用等）
     * - 不包含任务队列状态
     */
    public void createSnapshot() {
        RLock lock = redissonClient.getLock("node:management");
        try {
            lock.lock(5, TimeUnit.SECONDS);
            snapshotNodes.clear();
            for (Map.Entry<String, UAVNode> entry : nodes.entrySet()) {
                snapshotNodes.put(entry.getKey(), new UAVNode(entry.getValue()));
            }
            log.info("已创建集群快照, 节点数: {}.", snapshotNodes.size());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 从快照恢复集群状态
     *
     * 功能描述：
     * - 使用Redisson分布式锁保证线程安全
     * - 从snapshotNodes恢复所有节点状态
     * - 调用TaskService重新排队所有活跃任务
     * - 推送节点列表更新到WebSocket
     *
     * 注意：
     * - 如果快照为空则不执行恢复
     * - 恢复后任务状态会被重置为QUEUED
     */
    public void restoreSnapshot() {
        RLock lock = redissonClient.getLock("node:management");
        try {
            lock.lock(5, TimeUnit.SECONDS);
            if (snapshotNodes.isEmpty()) {
                log.warn("尝试恢复快照，但快照为空.");
                return;
            }
            nodes.clear();
            for (Map.Entry<String, UAVNode> entry : snapshotNodes.entrySet()) {
                nodes.put(entry.getKey(), new UAVNode(entry.getValue())); // Deep copy again so subsequent restores work
            }
            // 清空 Redis 任务队列和活跃映射，防止上一轮遗留数据污染下一轮算法对比
            redissonClient.getDeque("scheduler:task_queue").clear();
            redissonClient.getMap("task:active").clear();
            // 同步数据库中任务状态，重置为初始待提交
            taskService.resetAllTasksForSnapshot();
            log.info("已从快照恢复集群状态.");
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 获取所有节点列表
     *
     * @return 所有节点的列表（ArrayList副本）
     */
    public List<UAVNode> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * 获取指定节点
     *
     * @param id 节点ID
     * @return 节点对象（如果不存在返回null）
     */
    public UAVNode getNode(String id) {
        return nodes.get(id);
    }

    /**
     * 分配硬件资源到节点
     *
     * 功能描述：
     * - 使用Redisson分布式锁保证线程安全
     * - 检查节点是否在线且电量充足（>5%）
     * - 增加节点的CPU和内存使用量
     * - 增加节点的活跃任务计数
     *
     * @param nodeId 节点ID
     * @param requiredCpu 需要分配的CPU核心数
     * @param requiredMemory 需要分配的内存大小
     * @return 是否分配成功
     */
    public boolean allocate(String nodeId, double requiredCpu, double requiredMemory) {
        RLock lock = redissonClient.getLock("node:alloc:" + nodeId);
        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                log.warn("无法获取节点 {} 分配的Redisson锁", nodeId);
                return false;
            }
            UAVNode node = nodes.get(nodeId);
            if (node != null && node.isOnline() && node.getBattery() > 5.0) {
                double newCpuUsage = node.getCurrentCpuUsage() + requiredCpu;
                double newMemUsage = node.getCurrentMemoryUsage() + requiredMemory;
                if (newCpuUsage > node.getMaxCpu() || newMemUsage > node.getMaxMemory()) {
                    log.warn("节点 {} 资源不足: CPU={}/{}, Memory={}/{}",
                        nodeId, newCpuUsage, node.getMaxCpu(), newMemUsage, node.getMaxMemory());
                    return false;
                }
                node.setCurrentCpuUsage(newCpuUsage);
                node.setCurrentMemoryUsage(newMemUsage);
                node.getActiveTasksCount().incrementAndGet();
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (lock.isHeldByCurrentThread())
                lock.unlock();
        }
    }

    /**
     * 释放节点硬件资源
     *
     * 功能描述：
     * - 使用Redisson分布式锁保证线程安全
     * - 减少节点的CPU和内存使用量
     * - 减少节点的活跃任务计数
     * - 防止计数变为负数（兜底逻辑）
     *
     * @param nodeId 节点ID
     * @param releasedCpu 要释放的CPU核心数
     * @param releasedMemory 要释放的内存大小
     */
    public void release(String nodeId, double releasedCpu, double releasedMemory) {
        RLock lock = redissonClient.getLock("node:alloc:" + nodeId);
        try {
            lock.lock(5, TimeUnit.SECONDS);
            UAVNode node = nodes.get(nodeId);
            if (node != null) {
                node.setCurrentCpuUsage(Math.max(0, node.getCurrentCpuUsage() - releasedCpu));
                node.setCurrentMemoryUsage(Math.max(0, node.getCurrentMemoryUsage() - releasedMemory));
                int newCount = node.getActiveTasksCount().decrementAndGet();
                if (newCount < 0) {
                    node.getActiveTasksCount().set(0); // 兜底防止由于异步任务过期导致的负数溢出
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread())
                lock.unlock();
        }
    }

    /**
     * 定时模拟环境演进
     *
     * 功能描述：
     * - 每2秒执行一次（@Scheduled(fixedRate = 2000)）
     * - 使用Redisson分布式锁保证线程安全
     * - 模拟电池放电（基础+计算负载）
     * - 模拟光伏充能（SOLAR_SCOUT类型节点）
     * - 模拟航点巡航导航
     * - 自动触发RTH返航（电量<=15%）
     * - 自动触发坠毁（电量<=0）
     *
     * 环境模拟细节：
     * - 航点巡航：每40秒切换航点，移动速度2单位/2秒
     * - 电池放电：基础0.2%/2秒 + 计算负载消耗（×硬件能耗系数）
     * - 光伏充能：70%概率充能，+0.6%/2秒（SOLAR_SCOUT类型）
     * - RTH返航：速度3单位/2秒，充至100%后恢复巡逻
     */
    @Scheduled(fixedRate = 2000)
    public void simulateEnvironment() {
        RLock globalLock = redissonClient.getLock("cluster:link:simulation");
        try {
            // 虽然是单机模拟，但为了架构严谨性，加上分布式时钟同步锁（模拟）
            if (!globalLock.tryLock(0, 5, TimeUnit.SECONDS))
                return;

            boolean changed = false;
            for (UAVNode node : nodes.values()) {
                // 充电逻辑 (Charging Logic)
                if (node.isRthMode()) {
                    double targetX = 0.0;
                    double targetY = 0.0;
                    double currentRthSpeed = rthSpeed; // 自动返航 (RTH) 时的巡航速度稍快

                    double dx = targetX - node.getX();
                    double dy = targetY - node.getY();
                    double dist = Math.sqrt(dx * dx + dy * dy);

                    if (dist > currentRthSpeed) {
                        node.setX(node.getX() + (dx / dist) * currentRthSpeed);
                        node.setY(node.getY() + (dy / dist) * currentRthSpeed);
                    } else {
                        node.setX(0.0);
                        node.setY(0.0);
                        node.setCharging(true);
                    }

                    if (node.isCharging()) {
                        node.setBattery(Math.min(100.0, node.getBattery() + chargeRate)); // 每个时钟周期快速恢复电量
                        if (node.getBattery() >= 100.0) {
                            node.setRthMode(false);
                            node.setCharging(false);
                            node.setOnline(true);
                            node.setManualOverride(false); // 重置标志位，令其重新进入航线巡逻
                            log.info("{} 完成充电，正在返回巡逻任务.", node.getId());
                        }
                    }
                    changed = true;
                    continue;
                }

                if (!node.isOnline())
                    continue;

                // --- 航点巡航导航模拟 (Waypoint Navigation) ---
                if (!node.isManualOverride()) {
                    // 每个无人机按照每周期 ~2 个单位像素点的速度，向最近的分配航点驶去
                    int wpIndex = Math.abs(node.getId().hashCode()) % WAYPOINTS.length;
                    // 利用系统时间戳引发位移轮换，使无人机大约每转动 20 个周期 (~40 秒) 切换前往下一个拓扑坐标点
                    int rotatedIndex = (wpIndex + (int) (System.currentTimeMillis() / 40000)) % WAYPOINTS.length;
                    double targetX = WAYPOINTS[rotatedIndex][0];
                    double targetY = WAYPOINTS[rotatedIndex][1];

                    double dx = targetX - node.getX();
                    double dy = targetY - node.getY();
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    double currentMoveSpeed = moveSpeed; // 当前推力 (units per tick)

                    if (dist > currentMoveSpeed) {
                        node.setX(node.getX() + (dx / dist) * currentMoveSpeed);
                        node.setY(node.getY() + (dy / dist) * currentMoveSpeed);
                    } else {
                        // 已抵达目标航点，进入微弱的悬停抖动状态 (Jitter)
                        node.setX(targetX + (Math.random() * 2 - 1));
                        node.setY(targetY + (Math.random() * 2 - 1));
                    }
                }

                // 强制限制物理边界 (Clamp bounds)
                node.setX(Math.max(0, Math.min(100, node.getX())));
                node.setY(Math.max(0, Math.min(100, node.getY())));

                // Phase 2: 电池放电物理模型 (Hardware-profile-aware drain)
                // 基础航行待机掉电 + 挂载任务高负载消耗
                double baseDrain = baseDrainRate * node.getDrainRateMultiplier();
                double computeDrain = computeDrainMultiplier * node.getDrainRateMultiplier()
                        * (node.getCurrentCpuUsage() / Math.max(1, node.getMaxCpu()));
                double totalDrain = baseDrain + computeDrain;

                // Phase 2: Solar Harvesting — 光伏面板被动充能 (当未处于充满/返航状态时)
                double solarGain = 0.0;
                if (node.isSolarHarvesting() && !node.isRthMode() && node.getBattery() < 90.0) {
                    // 模拟光照随机性 (e.g. 云层遮挡) — 以 70% 概率进行充能
                    if (Math.random() < 0.7) {
                        solarGain = node.getSolarChargeRate();
                    }
                }

                node.setBattery(Math.max(0, Math.min(100.0, node.getBattery() - totalDrain + solarGain)));
                if (solarGain > 0) {
                    log.debug("[光伏] {} 从太阳能面板获取了 {:.2f}% 电量", node.getId(), solarGain);
                }

                // 根据阈值自动触发紧急返航 (RTH Auto-Trigger)
                if (node.getBattery() <= rthThreshold && node.isOnline()) {
                    node.setOnline(false); // 停止接收来自调度器的新派发订单
                    node.setRthMode(true);
                    log.warn("{} 电池电量达到临界值 ({}%). 正在启动返航 (RTH) 程序.",
                            node.getId(), rthThreshold);

                    // 触发任务主动迁移，防止任务长时间阻塞在返航无人机上
                    taskService.migrateTasksFromNode(node.getId());
                }

                // 彻底断电，模拟无人机空中坠毁 (Auto offline if completely dead)
                if (node.getBattery() <= 0 && node.isOnline()) { // Only trigger if currently online
                    log.error("{} 电池完全耗尽. 坠毁.", node.getId());
                    node.setRthMode(false);
                    node.setCharging(false);
                    node.setOnline(false); // Make it offline first to stop further allocations

                    // Re-queue the tasks that were running on this dropped node
                    taskService.requeueActiveTasksForNode(node.getId());

                    // Notice websocket explicitly about the fault injection
                    java.util.Map<String, String> faultInfo = new java.util.HashMap<>();
                    faultInfo.put("type", "FAULT_RECOVERY");
                    faultInfo.put("message", "信标 " + node.getName() + " 电力耗尽坠毁。正在重新调度其承载的流转任务...");
                    messagingTemplate.convertAndSend("/topic/notifications", faultInfo);

                    // Format reset hardware state
                    node.setActiveTasksCount(new java.util.concurrent.atomic.AtomicInteger(0));
                    node.setCurrentCpuUsage(0.0);
                    node.setCurrentMemoryUsage(0.0);
                }

                changed = true;
            }

            if (changed) {
                messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (globalLock.isHeldByCurrentThread())
                globalLock.unlock();
        }
    }

    /**
     * 设置节点在线/离线状态（故障注入）
     *
     * 功能描述：
     * - 用于系统故障注入和混沌工程测试
     * - 下线节点：重新排队该节点所有活跃任务，重置资源计数
     * - 上线节点：恢复电池至100%，退出RTH和充电模式
     *
     * @param nodeId 节点ID
     * @param online 是否在线
     */
    public void setNodeStatus(String nodeId, boolean online) {
        UAVNode node = nodes.get(nodeId);
        if (node != null) {
            node.setOnline(online);
            if (!online) {
                log.warn("故障注入: {} 已下线!", nodeId);
                taskService.requeueActiveTasksForNode(nodeId);
                node.setActiveTasksCount(new java.util.concurrent.atomic.AtomicInteger(0));
                node.setCurrentCpuUsage(0.0);
                node.setCurrentMemoryUsage(0.0);
            } else {
                node.setBattery(100.0);
                node.setRthMode(false);
                node.setCharging(false);
                log.info("恢复: {} 已上线，电池已充满.", nodeId);
            }
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
        }
    }

    /**
     * 更新节点硬件配置
     *
     * 功能描述：
     * - 动态调整节点的CPU、内存、带宽配置
     * - 只更新非null参数（可选更新）
     * - 推送更新后的节点列表到WebSocket
     *
     * @param nodeId 节点ID
     * @param maxCpu 最大CPU（可选）
     * @param maxMemory 最大内存（可选）
     * @param networkBandwidth 网络带宽（可选）
     * @return 更新后的节点对象（不存在返回null）
     */
    public UAVNode updateNodeConfig(String nodeId, Double maxCpu, Double maxMemory, Double networkBandwidth) {
        UAVNode node = nodes.get(nodeId);
        if (node != null) {
            if (maxCpu != null)
                node.setMaxCpu(maxCpu);
            if (maxMemory != null)
                node.setMaxMemory(maxMemory);
            if (networkBandwidth != null)
                node.setNetworkBandwidth(networkBandwidth);
            log.info("节点 {} 配置已更新: CPU={}, RAM={}, BW={}", nodeId, maxCpu, maxMemory, networkBandwidth);
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
            return node;
        }
        return null;
    }
}
