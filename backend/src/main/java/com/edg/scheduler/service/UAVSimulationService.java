package com.edg.scheduler.service;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.TaskTraceLog;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.repository.TaskRepository;
import com.edg.scheduler.repository.TaskTraceLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UAV仿真服务
 *
 * 核心职责：
 * - 管理所有无人机 worker 线程的生命周期
 * - 每个 UAV 独立线程执行任务
 * - 支持任务队列和异步任务执行
 *
 * 架构：
 * - UAVSimulationService: 管理所有 UAV worker
 * - UAVWorker: 每个 UAV 的独立执行线程
 */
@Slf4j
@Service
public class UAVSimulationService {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskTraceLogRepository traceLogRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Value("${app.scheduler.simulation.uav-thread-enabled:true}")
    private boolean uavThreadEnabled;

    @Value("${app.scheduler.simulation.distance-delay-ms-per-unit:5.0}")
    private double distanceDelayMsPerUnit;

    private final Map<String, UAVWorker> workers = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<TaskInfo>> taskQueues = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> workerRunning = new ConcurrentHashMap<>();

    private ExecutorService workerExecutor;

    /**
     * 初始化 UAV worker 线程
     * 在 NodeService 初始化节点后调用
     */
    @PostConstruct
    public void init() {
        if (!uavThreadEnabled) {
            log.info("UAV仿真线程已禁用，使用旧版异步模拟");
            return;
        }

        workerExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "uav-worker-");
            t.setDaemon(true);
            return t;
        });

        // 为每个已有节点创建 worker
        for (UAVNode node : nodeService.getAllNodes()) {
            createWorkerForNode(node.getId());
        }

        log.info("UAVSimulationService 初始化完成, workers={}", workers.size());
    }

    /**
     * 为指定节点创建 worker 线程
     */
    public void createWorkerForNode(String nodeId) {
        if (!uavThreadEnabled) return;
        if (workers.containsKey(nodeId)) return;

        BlockingQueue<TaskInfo> queue = new LinkedBlockingQueue<>();
        taskQueues.put(nodeId, queue);

        AtomicBoolean running = new AtomicBoolean(true);
        workerRunning.put(nodeId, running);

        UAVWorker worker = new UAVWorker(nodeId, queue, running);
        workers.put(nodeId, worker);

        workerExecutor.execute(worker);
        log.info("已为节点 {} 创建 UAV worker 线程", nodeId);
    }

    /**
     * 停止指定节点的 worker 线程
     */
    public void stopWorkerForNode(String nodeId) {
        AtomicBoolean running = workerRunning.get(nodeId);
        if (running != null) {
            running.set(false);
        }
        workers.remove(nodeId);
        taskQueues.remove(nodeId);
        log.info("已停止节点 {} 的 UAV worker 线程", nodeId);
    }

    /**
     * 投递任务到指定 UAV 的任务队列
     *
     * @param nodeId UAV 节点ID
     * @param task 待执行的任务
     * @return 是否成功投递
     */
    public boolean enqueueTask(String nodeId, TaskInfo task) {
        if (!uavThreadEnabled) {
            return false; // 降级到旧逻辑
        }

        BlockingQueue<TaskInfo> queue = taskQueues.get(nodeId);
        if (queue == null) {
            log.warn("未找到节点 {} 的任务队列，正在创建", nodeId);
            createWorkerForNode(nodeId);
            queue = taskQueues.get(nodeId);
        }

        if (queue != null) {
            return queue.offer(task);
        }
        return false;
    }

    /**
     * 获取 UAV 的任务队列大小
     */
    public int getQueueSize(String nodeId) {
        BlockingQueue<TaskInfo> queue = taskQueues.get(nodeId);
        return queue != null ? queue.size() : 0;
    }

    /**
     * UAV Worker 线程类
     * 每个 UAV 节点拥有独立的 worker 线程，不断从队列获取任务并执行
     */
    class UAVWorker implements Runnable {
        private final String nodeId;
        private final BlockingQueue<TaskInfo> taskQueue;
        private final AtomicBoolean running;

        UAVWorker(String nodeId, BlockingQueue<TaskInfo> taskQueue, AtomicBoolean running) {
            this.nodeId = nodeId;
            this.taskQueue = taskQueue;
            this.running = running;
        }

        @Override
        public void run() {
            log.info("UAV worker {} 已启动", nodeId);

            while (running.get()) {
                try {
                    // 阻塞等待任务，最长1秒检查一次 running 状态
                    TaskInfo task = taskQueue.poll(1, TimeUnit.SECONDS);
                    if (task == null) {
                        continue;
                    }

                    executeTask(task);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("UAV worker {} 遇到错误", nodeId, e);
                }
            }

            log.info("UAV worker {} 已停止", nodeId);
        }

        /**
         * 执行单个任务
         */
        private void executeTask(TaskInfo task) {
            UAVNode node = nodeService.getNode(nodeId);
            if (node == null) {
                log.warn("未找到节点 {} 用于任务执行", nodeId);
                return;
            }

            log.info("UAV {} 正在执行任务 {}", nodeId, task.getId());

            // 计算传输延迟
            double distance = calculateDistance(node.getX(), node.getY(), task.getOriginX(), task.getOriginY());
            long transmissionDelay = (long) (distance * distanceDelayMsPerUnit);

            // 计算执行时间
            long executionTimeMs = (long) ((task.getDataSize() / node.getMaxCpu()) * 100);
            executionTimeMs = Math.max(2000, Math.min(10000, executionTimeMs * 10)) + transmissionDelay;

            // 模拟任务执行（sleep）
            try {
                Thread.sleep(executionTimeMs);
            } catch (InterruptedException e) {
                task.setStatus("FAILED");
                Thread.currentThread().interrupt();
                return;
            }

            // 状态栅栏检查
            TaskInfo latestTask = taskRepository.findById(task.getId()).orElse(null);
            if (latestTask == null || !"RUNNING_EDGE".equals(latestTask.getStatus())) {
                log.warn("任务 {} 在执行期间状态已变更，中止完成处理", task.getId());
                return;
            }

            // 完成任务
            task.setStatus("COMPLETED");
            task.setEndTime(System.currentTimeMillis());

            double computingPower = 5.0;
            double timeInSec = (System.currentTimeMillis() - task.getStartTime()) / 1000.0;
            task.setActualEnergyUsed(computingPower * timeInSec);

            // 更新追踪日志
            TaskTraceLog traceLog = traceLogRepository.findByTaskId(task.getId());
            if (traceLog != null) {
                long now = System.currentTimeMillis();
                traceLog.setExecutionEndTime(now);
                traceLog.setComputeLatency(now - traceLog.getExecutionStartTime());
                traceLogRepository.save(traceLog);
            }

            taskRepository.save(task);
            redissonClient.getMap("task:active").remove(task.getId());
            nodeService.release(nodeId, task.getRequiredCpu(), task.getRequiredMemory());

            log.info("UAV {} 已完成任务 {} (能耗: {} J)", nodeId, task.getId(),
                    String.format("%.2f", task.getActualEnergyUsed()));

            // 广播状态更新
            broadcastState();
            broadcastTaskUpdate(task);
        }

        private double calculateDistance(double x1, double y1, double x2, double y2) {
            return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
        }

        private void broadcastState() {
            messagingTemplate.convertAndSend("/topic/nodes", nodeService.getAllNodes());
        }

        private void broadcastTaskUpdate(TaskInfo task) {
            messagingTemplate.convertAndSend("/topic/tasks", task);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("正在关闭 UAVSimulationService...");
        for (AtomicBoolean running : workerRunning.values()) {
            running.set(false);
        }

        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
            }
        }

        workers.clear();
        taskQueues.clear();
        log.info("UAVSimulationService 关闭完成");
    }
}