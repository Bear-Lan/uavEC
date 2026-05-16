package com.edg.scheduler.service;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.TaskTraceLog;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 云端仿真服务
 *
 * 核心职责：
 * - 模拟云端任务队列
 * - 跟踪云端队列状态（排队长度、吞吐量、CPU使用率）
 * - 更新 Redis 中的 cloud:stats 供卸载策略使用
 * - 异步执行云端任务
 */
@Slf4j
@Service
public class CloudSimulationService {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Value("${app.scheduler.cloud.cpu-cores:64.0}")
    private double cloudCpuCores;

    @Value("${app.scheduler.cloud.bandwidth-mbps:50.0}")
    private double cloudBandwidthMbps;

    @Value("${app.scheduler.cloud.service-rate:1.0}")
    private double cloudServiceRate;  // 任务/秒

    private static final String CLOUD_STATS_KEY = "cloud:stats";

    private final BlockingQueue<TaskInfo> cloudQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger processingCount = new AtomicInteger(0);
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger totalArrivals = new AtomicInteger(0);

    private ScheduledExecutorService statsScheduler;
    private ExecutorService workerExecutor;

    /**
     * 初始化云端仿真服务
     */
    @PostConstruct
    public void init() {
        statsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloud-stats-updater");
            t.setDaemon(true);
            return t;
        });

        workerExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "cloud-worker-");
            t.setDaemon(true);
            return t;
        });

        // 定期更新 cloud:stats
        statsScheduler.scheduleAtFixedRate(this::updateCloudStats, 0, 500, TimeUnit.MILLISECONDS);

        // 启动云端 worker 处理队列
        for (int i = 0; i < 4; i++) {
            workerExecutor.submit(this::cloudWorkerLoop);
        }

        log.info("CloudSimulationService 初始化完成, workers={}, 服务率={} 任务/秒",
                4, cloudServiceRate);
    }

    /**
     * 云端 worker 循环，从队列取任务并执行
     */
    private void cloudWorkerLoop() {
        log.info("云端 worker 线程已启动");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TaskInfo task = cloudQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }
                executeCloudTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("云端 worker 发生错误", e);
            }
        }
        log.info("云端 worker 线程已停止");
    }

    /**
     * 分发任务到云端队列
     *
     * @param task 任务
     * @return 是否成功
     */
    public boolean enqueueTask(TaskInfo task) {
        totalArrivals.incrementAndGet();
        boolean offered = cloudQueue.offer(task);
        if (offered) {
            updateQueueLength();
            log.info("任务 {} 已进入云端队列, 当前队列长度: {}", task.getId(), cloudQueue.size());
        }
        return offered;
    }

    /**
     * 执行云端任务
     */
    private void executeCloudTask(TaskInfo task) {
        processingCount.incrementAndGet();
        updateQueueLength();

        try {
            double dataSizeMB = task.getDataSize();

            // 计算执行时间（模拟云端计算）
            long computeTimeMs = (long) ((dataSizeMB / cloudCpuCores) * 100);
            long wanLatencyMs = 50;  // WAN延迟
            long txTimeMs = (long) ((dataSizeMB / cloudBandwidthMbps) * 1000);

            long totalTimeMs = computeTimeMs + wanLatencyMs + txTimeMs;
            totalTimeMs = Math.max(100, totalTimeMs);

            Thread.sleep(totalTimeMs);

            completedCount.incrementAndGet();
            task.setStatus("COMPLETED");
            task.setEndTime(System.currentTimeMillis());

            // 能耗：传输能耗
            double txTimeSec = txTimeMs / 1000.0;
            double txPower = 2.0;
            task.setActualEnergyUsed(txPower * txTimeSec);

            log.info("云端完成任务 {} 完成, 耗时 {}ms", task.getId(), totalTimeMs);

        } catch (InterruptedException e) {
            task.setStatus("FAILED");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("云端任务 {} 执行失败", task.getId(), e);
            task.setStatus("FAILED");
        } finally {
            processingCount.decrementAndGet();
            updateQueueLength();
        }
    }

    /**
     * 更新云端队列长度到 Redis
     */
    private void updateQueueLength() {
        try {
            RMap<String, Object> stats = redissonClient.getMap(CLOUD_STATS_KEY);
            stats.put("queueLength", cloudQueue.size());
            stats.put("processing", processingCount.get());
            stats.put("completedTotal", completedCount.get());
            stats.put("arrivalsTotal", totalArrivals.get());
            stats.put("lastUpdate", System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("云端状态更新失败: {}", e.getMessage());
        }
    }

    /**
     * 定期更新统计信息到 Redis
     * 计算到达率 lambda 和当前队列长度
     */
    private void updateCloudStats() {
        try {
            int queueLen = cloudQueue.size();
            int processing = processingCount.get();

            // 估算到达率（基于最近的时间窗口）
            // 使用简单的滑动平均：lambda = 完成数 / 时间窗口
            int completed = completedCount.get();

            RMap<String, Object> stats = redissonClient.getMap(CLOUD_STATS_KEY);
            stats.put("queueLength", queueLen);
            stats.put("processing", processing);
            stats.put("completedTotal", completed);
            stats.put("arrivalsTotal", totalArrivals.get());
            stats.put("serviceRate", cloudServiceRate);

            // 计算利用率
            double utilization = (queueLen + processing) / cloudCpuCores;
            stats.put("utilization", Math.min(1.0, utilization));

            // 计算估算等待时间
            double estimatedWaitSec = queueLen / cloudServiceRate;
            stats.put("estimatedWaitSec", estimatedWaitSec);

            stats.put("lastUpdate", System.currentTimeMillis());

            // 广播云端状态更新
            broadcastCloudUpdate();

        } catch (Exception e) {
            log.debug("Failed to update cloud stats periodically: {}", e.getMessage());
        }
    }

    /**
     * 获取当前队列长度
     */
    public int getQueueLength() {
        return cloudQueue.size();
    }

    /**
     * 获取当前处理中的任务数
     */
    public int getProcessingCount() {
        return processingCount.get();
    }

    /**
     * 获取云端统计信息
     */
    public CloudStats getStats() {
        return new CloudStats(
                cloudQueue.size(),
                processingCount.get(),
                completedCount.get(),
                totalArrivals.get(),
                cloudCpuCores,
                cloudServiceRate
        );
    }

    /**
     * 广播云端状态更新到 WebSocket
     */
    private void broadcastCloudUpdate() {
        try {
            CloudStats stats = getStats();
            messagingTemplate.convertAndSend("/topic/cloud", stats);
        } catch (Exception e) {
            log.debug("Failed to broadcast cloud update: {}", e.getMessage());
        }
    }

    /**
     * 云端统计信息
     */
    public static class CloudStats {
        private final int queueLength;
        private final int processing;
        private final int completedTotal;
        private final int arrivalsTotal;
        private final double cpuCores;
        private final double serviceRate;

        public CloudStats(int queueLength, int processing, int completedTotal,
                         int arrivalsTotal, double cpuCores, double serviceRate) {
            this.queueLength = queueLength;
            this.processing = processing;
            this.completedTotal = completedTotal;
            this.arrivalsTotal = arrivalsTotal;
            this.cpuCores = cpuCores;
            this.serviceRate = serviceRate;
        }

        public int getQueueLength() { return queueLength; }
        public int getProcessing() { return processing; }
        public int getCompletedTotal() { return completedTotal; }
        public int getArrivalsTotal() { return arrivalsTotal; }
        public double getCpuCores() { return cpuCores; }
        public double getServiceRate() { return serviceRate; }
    }
}