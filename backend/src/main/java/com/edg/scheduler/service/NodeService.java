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

    // Phase 2: Assign a heterogeneous hardware profile to a new UAV node
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
        log.info("Initialized {} heterogeneous UAV nodes", initialNodeCount);
    }

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
            log.info("Dynamically added new UAV node: {} (profile: {})", id, newNode.getHardwareProfile());
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
            return newNode;
        } finally {
            if (lock.isHeldByCurrentThread())
                lock.unlock();
        }
    }

    public boolean deleteNode(String nodeId) {
        RLock lock = redissonClient.getLock("node:management");
        try {
            lock.lock(5, TimeUnit.SECONDS);
            if (nodes.containsKey(nodeId)) {
                nodes.remove(nodeId);
                log.info("Dynamically removed UAV node: {}", nodeId);
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

    // 暴露供紧急手动回血的 API 接口
    public void emergencyCharge(String nodeId) {
        UAVNode node = nodes.get(nodeId);
        if (node != null) {
            node.setBattery(100.0);
            node.setOnline(true);
            node.setRthMode(false);
            node.setCharging(false);
            node.setManualOverride(false); // 解除锚定锁定，恢复自动巡逻
            log.info("Emergency Override: {} battery replenished to 100%", nodeId);
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
        }
    }

    // --- Sandbox Isolation Mechanism ---

    public void createSnapshot() {
        RLock lock = redissonClient.getLock("node:management");
        try {
            lock.lock(5, TimeUnit.SECONDS);
            snapshotNodes.clear();
            for (Map.Entry<String, UAVNode> entry : nodes.entrySet()) {
                snapshotNodes.put(entry.getKey(), new UAVNode(entry.getValue()));
            }
            log.info("Created cluster snapshot with {} nodes.", snapshotNodes.size());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void restoreSnapshot() {
        RLock lock = redissonClient.getLock("node:management");
        try {
            lock.lock(5, TimeUnit.SECONDS);
            if (snapshotNodes.isEmpty()) {
                log.warn("Attempted to restore snapshot, but snapshot was empty.");
                return;
            }
            nodes.clear();
            for (Map.Entry<String, UAVNode> entry : snapshotNodes.entrySet()) {
                nodes.put(entry.getKey(), new UAVNode(entry.getValue())); // Deep copy again so subsequent restores work
            }
            // Clear current task assignments from tasks in Redis queue
            taskService.requeueAllActiveTasks();
            log.info("Restored cluster state from snapshot.");
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public List<UAVNode> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    public UAVNode getNode(String id) {
        return nodes.get(id);
    }

    /**
     * 基于分布式锁保护的核心硬件资源扣减分配 (使用 Redisson 取代本地 synchronized 关键字以支持微服务扩容)。
     */
    public boolean allocate(String nodeId, double requiredCpu, double requiredMemory) {
        RLock lock = redissonClient.getLock("node:alloc:" + nodeId);
        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                log.warn("Could not acquire Redisson lock for node {} allocation", nodeId);
                return false;
            }
            UAVNode node = nodes.get(nodeId);
            if (node != null && node.isOnline() && node.getBattery() > 5.0) {
                node.setCurrentCpuUsage(node.getCurrentCpuUsage() + requiredCpu);
                node.setCurrentMemoryUsage(node.getCurrentMemoryUsage() + requiredMemory);
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
     * 基于分布式锁保护的硬件资源归还释放。
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
     * 定时模拟环境演进：包含电池物理消耗、航点导航、以及低电量自动返航与坠毁断线逻辑。
     * 这些航点让 "geo" (地理拓扑) 调度算法真正有了在物理世界航迹上运作的实感。
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
                            log.info("{} completed charging and is returning to active duty on patrol.", node.getId());
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
                    log.debug("[SOLAR] {} harvested {:.2f}% energy from solar panels", node.getId(), solarGain);
                }

                // 根据阈值自动触发紧急返航 (RTH Auto-Trigger)
                if (node.getBattery() <= rthThreshold && node.isOnline()) {
                    node.setOnline(false); // 停止接收来自调度器的新派发订单
                    node.setRthMode(true);
                    log.warn("{} reached critical battery ({}%). Initiating Return-To-Home (RTH) sequence.",
                            node.getId(), rthThreshold);

                    // 触发任务主动迁移，防止任务长时间阻塞在返航无人机上
                    taskService.migrateTasksFromNode(node.getId());
                }

                // 彻底断电，模拟无人机空中坠毁 (Auto offline if completely dead)
                if (node.getBattery() <= 0 && node.isOnline()) { // Only trigger if currently online
                    log.error("{} battery completely dead. Crashed.", node.getId());
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

    // 暴露供系统故障注入和混沌工程测试的 API 接口
    public void setNodeStatus(String nodeId, boolean online) {
        UAVNode node = nodes.get(nodeId);
        if (node != null) {
            node.setOnline(online);
            if (!online) {
                log.warn("Fault Injection: {} taken offline!", nodeId);
                taskService.requeueActiveTasksForNode(nodeId);
                node.setActiveTasksCount(new java.util.concurrent.atomic.AtomicInteger(0));
                node.setCurrentCpuUsage(0.0);
                node.setCurrentMemoryUsage(0.0);
            } else {
                node.setBattery(100.0);
                node.setRthMode(false);
                node.setCharging(false);
                log.info("Recovery: {} brought online with full battery.", nodeId);
            }
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
        }
    }

    public UAVNode updateNodeConfig(String nodeId, Double maxCpu, Double maxMemory, Double networkBandwidth) {
        UAVNode node = nodes.get(nodeId);
        if (node != null) {
            if (maxCpu != null)
                node.setMaxCpu(maxCpu);
            if (maxMemory != null)
                node.setMaxMemory(maxMemory);
            if (networkBandwidth != null)
                node.setNetworkBandwidth(networkBandwidth);
            log.info("Node {} config updated: CPU={}, RAM={}, BW={}", nodeId, maxCpu, maxMemory, networkBandwidth);
            messagingTemplate.convertAndSend("/topic/nodes", getAllNodes());
            return node;
        }
        return null;
    }
}
