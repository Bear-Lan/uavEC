package com.edg.scheduler.service;

import com.edg.scheduler.model.DispatchResult;
import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.repository.TaskRepository;
import com.edg.scheduler.repository.TaskTraceLogRepository;
import com.edg.scheduler.model.TaskTraceLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

import org.redisson.api.RedissonClient;
import org.redisson.api.RMap;

@Slf4j
@Service
public class TaskService {

    private static final String TASK_QUEUE_KEY = "scheduler:task_queue";

    @Autowired
    @org.springframework.context.annotation.Lazy
    private NodeService nodeService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskTraceLogRepository traceLogRepository;

    @Autowired
    private DispatchService dispatchService;

    @Transactional
    public String submitTask(TaskInfo task) {
        if (task.getId() == null) {
            task.setId(UUID.randomUUID().toString());
        }
        if (task.getTaskName() == null || task.getTaskName().trim().isEmpty()) {
            String prefix = (task.getOperatorName() != null && !task.getOperatorName().isBlank())
                    ? task.getOperatorName()
                    : "Ops";
            task.setTaskName(prefix + "-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date())
                    + "-" + task.getId().substring(0, 4).toUpperCase());
        }
        task.setSubmitTime(System.currentTimeMillis());
        task.setStatus("QUEUED");

        // 将任务落库持久化 (MySQL)
        taskRepository.save(task);

        // 初始化细粒度的底层执行流水日志 (Trace Log)
        TaskTraceLog traceLog = new TaskTraceLog();
        traceLog.setTaskId(task.getId());
        traceLog.setCreatedTime(System.currentTimeMillis());
        traceLogRepository.save(traceLog);

        // 支持优先级的 Redisson 队列: 具备高优先级 (>=4) 的特权任务直接“插队”到队首 (HEAD)
        org.redisson.api.RDeque<TaskInfo> queue = redissonClient.getDeque(TASK_QUEUE_KEY);
        if (task.getPriority() >= 4) {
            queue.addFirst(task);
            log.info("Task {} (priority={}) submitted with HEAD priority to Redisson queue", task.getId(),
                    task.getPriority());
        } else {
            queue.addLast(task);
            log.info("Task {} (priority={}) submitted to Redisson queue", task.getId(), task.getPriority());
        }

        return task.getId();
    }

    @Scheduled(fixedDelay = 500)
    public void processTaskQueue() {
        // 每次最多从队列头消费 10 个周期任务，显著拔高系统吞吐力上限
        int maxPerTick = 10;
        org.redisson.api.RDeque<TaskInfo> queue = redissonClient.getDeque(TASK_QUEUE_KEY);

        for (int i = 0; i < maxPerTick; i++) {
            TaskInfo task = queue.pollFirst();
            if (task == null)
                break;

            log.info("Processing task {} from Redisson queue", task.getId());

            // 瀑布流追踪检查点 2: 出队完毕 -> 即将调遣 (Dequeued -> Dispatching)
            TaskTraceLog traceLog = traceLogRepository.findByTaskId(task.getId());
            if (traceLog != null) {
                long now = System.currentTimeMillis();
                traceLog.setDequeuedTime(now);
                traceLog.setQueueLatency(now - traceLog.getCreatedTime());
                traceLogRepository.save(traceLog);
            }

            task.setStatus("DISPATCHING");
            taskRepository.save(task);

            DispatchResult result = dispatchService.dispatch(task);

            if (result.isSuccess()) {
                taskRepository.save(task);
            } else {
                log.warn("Dispatch failed for task {}: {}. Requeuing.", task.getId(), result.getMessage());
                queue.addFirst(task);
            }
        }
    }

    @Autowired
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    /**
     * 阶段 5 架构: 单点故障容错演练 (Fault Tolerance)
     * 系统将会拉取、剔除掉所有挂载在已经坠毁 (Failed) 无人机节点上的半路活跃任务并引发全部重排。
     */
    @Transactional
    public void requeueActiveTasksForNode(String nodeId) {
        log.warn("Initiating recovery for node {}. Finding active tasks...", nodeId);
        java.util.List<TaskInfo> strandedTasks = taskRepository.findByAssignedUavId(nodeId);

        int recovered = 0;
        for (TaskInfo task : strandedTasks) {
            String s = task.getStatus();
            if (s != null && (s.startsWith("RUNNING") || s.equals("DISPATCHING"))) {
                task.setStatus("QUEUED");
                task.setAssignedUavId(null);
                taskRepository.save(task);

                redissonClient.getDeque(TASK_QUEUE_KEY).addLast(task);
                recovered++;
                log.info("Recovered Task {} from fallen node {}", task.getId(), nodeId);
            }
        }
        log.info("Recovery complete for node {}. {} tasks requeued.", nodeId, recovered);

        // 向前端总线推送紧急降级流转面板信息
        if (recovered > 0) {
            java.util.Map<String, Object> notification = new java.util.HashMap<>();
            notification.put("type", "FAULT_RECOVERY");
            notification.put("nodeId", nodeId);
            notification.put("recoveredCount", recovered);
            notification.put("message", "节点 " + nodeId + " 宕机，" + recovered + " 个任务已自动重新排队");
            messagingTemplate.convertAndSend("/topic/notifications", notification);
        }
    }

    /**
     * 迁移指定节点上的所有活跃边缘计算任务。
     * 当无人机进入 RTH (返航充电) 模式时触发，以防止任务因充电过程而超时。
     */
    @Transactional
    public void migrateTasksFromNode(String nodeId) {
        log.info("RTH MIGRATION: Initiating proactive migration for node {}...", nodeId);
        java.util.List<TaskInfo> activeTasks = taskRepository.findByAssignedUavId(nodeId);

        int migrated = 0;
        int requeued = 0;

        for (TaskInfo task : activeTasks) {
            if ("RUNNING_EDGE".equals(task.getStatus())) {
                boolean transferSuccess = false;

                // 尝试寻找一个最优邻居节点进行无缝迁移
                for (com.edg.scheduler.model.UAVNode neighbor : nodeService.getAllNodes()) {
                    if (neighbor.isOnline() && !neighbor.isRthMode() && !neighbor.getId().equals(nodeId)) {
                        if (neighbor.getAvailableCpu() >= task.getRequiredCpu() &&
                                neighbor.getAvailableMemory() >= task.getRequiredMemory()) {

                            // 执行模拟迁移：更新资源分配并更改挂载节点
                            nodeService.release(nodeId, task.getRequiredCpu(), task.getRequiredMemory());
                            nodeService.allocate(neighbor.getId(), task.getRequiredCpu(), task.getRequiredMemory());

                            task.setAssignedUavId(neighbor.getId());
                            taskRepository.save(task);

                            log.info("RTH MIGRATION: Task {} migrated from {} to neighbor {}",
                                    task.getId(), nodeId, neighbor.getId());

                            migrated++;
                            transferSuccess = true;
                            break;
                        }
                    }
                }

                // 如果没有找到合适的邻居，则强制重回全局队列，标记为高优先级
                if (!transferSuccess) {
                    nodeService.release(nodeId, task.getRequiredCpu(), task.getRequiredMemory());
                    task.setStatus("QUEUED");
                    task.setAssignedUavId(null);
                    task.setPriority(Math.max(task.getPriority(), 4)); // 提升至特权级
                    taskRepository.save(task);

                    redissonClient.getDeque(TASK_QUEUE_KEY).addFirst(task);
                    requeued++;
                    log.info("RTH MIGRATION: No neighbor for Task {}, forced requeue with high priority.",
                            task.getId());
                }
            }
        }

        if (migrated > 0 || requeued > 0) {
            dispatchService.broadcastState();
            java.util.Map<String, Object> notification = new java.util.HashMap<>();
            notification.put("type", "RTH_MIGRATION");
            notification.put("message", "信标 " + nodeId + " 进入返航模式，已迁移 " + migrated + " 个任务，重排 " + requeued + " 个任务。");
            messagingTemplate.convertAndSend("/topic/notifications", notification);
        }
    }

    /**
     * 阶段 6 架构: 动态工作窃取算法调度机制 (Work Stealing Algorithm)
     * 系统扫描轮询时，极度空闲的边缘计算节点将会自发去拦截并“窃取”邻近因故障积压死锁节点的阻塞任务队列。
     */
    @Scheduled(fixedDelay = 2000)
    public void workStealingPoller() {
        for (com.edg.scheduler.model.UAVNode idleNode : nodeService.getAllNodes()) {
            if (idleNode.isOnline() && !idleNode.isRthMode() && idleNode.getActiveTasksCount().get() == 0) {
                // 搜索一个被严重堵死的节点 (比如当前并发压载任务数量阈值 >= 2 的节点)
                for (com.edg.scheduler.model.UAVNode busyNode : nodeService.getAllNodes()) {
                    if (busyNode.isOnline() && !busyNode.equals(idleNode)
                            && busyNode.getActiveTasksCount().get() >= 2) {
                        try {
                            java.util.List<TaskInfo> strandedTasks = taskRepository
                                    .findByAssignedUavId(busyNode.getId());
                            for (TaskInfo task : strandedTasks) {
                                if ("RUNNING_EDGE".equals(task.getStatus())) {
                                    // 窃取判定通过! 转交控制权
                                    task.setAssignedUavId(idleNode.getId());

                                    // 进行交接前的兜底检查，二次确认其可承担容量
                                    if (idleNode.getAvailableCpu() >= task.getRequiredCpu()
                                            && idleNode.getAvailableMemory() >= task.getRequiredMemory()) {
                                        taskRepository.save(task);

                                        // 全局红黑树参数矫正
                                        nodeService.release(busyNode.getId(), task.getRequiredCpu(),
                                                task.getRequiredMemory());
                                        nodeService.allocate(idleNode.getId(), task.getRequiredCpu(),
                                                task.getRequiredMemory());

                                        log.info("WORK STEALING: Idle {} stole Task {} from overloaded {}",
                                                idleNode.getId(), task.getId(), busyNode.getId());

                                        // 追平修补底层信道链路流转细节 (Trace Log)
                                        TaskTraceLog traceLog = traceLogRepository.findByTaskId(task.getId());
                                        if (traceLog != null) {
                                            traceLog.setAssignedUavId(idleNode.getId());
                                            traceLogRepository.save(traceLog);
                                        }

                                        // 通知 UI 面板并闪烁强心剂警告
                                        dispatchService.broadcastState();

                                        java.util.Map<String, Object> notification = new java.util.HashMap<>();
                                        notification.put("type", "WORK_STEALING");
                                        notification.put("message", "工作窃取算法触发: 闲置节点 " + idleNode.getId() + " 分担了 "
                                                + busyNode.getId() + " 的拥挤任务！");
                                        notification.put("fromNodeId", busyNode.getId());
                                        notification.put("toNodeId", idleNode.getId());
                                        notification.put("taskId", task.getId());
                                        messagingTemplate.convertAndSend("/topic/notifications", notification);

                                        return; // 为了防止雪崩级系统抖动，每个心跳节拍区间内仅准许发生一笔全局工作窃取流转
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.error("Work stealing error", e);
                        }
                    }
                }
            }
        }
    }

    @Transactional
    public void requeueAllActiveTasks() {
        RMap<String, TaskInfo> activeMap = redissonClient.getMap("task:active");
        if (activeMap.isEmpty())
            return;

        List<TaskInfo> tasksToRequeue = new ArrayList<>(activeMap.values());
        for (TaskInfo task : tasksToRequeue) {
            String uavId = task.getAssignedUavId();
            if (uavId != null && !uavId.startsWith("CLOUD")) {
                nodeService.release(uavId, task.getRequiredCpu(), task.getRequiredMemory());
            }

            task.setStatus("QUEUED");
            task.setAssignedUavId(null);

            activeMap.remove(task.getId());
            redissonClient.getDeque(TASK_QUEUE_KEY).addLast(task);
            log.info("Task {} requeued due to cluster rollback", task.getId());
        }
        messagingTemplate.convertAndSend("/topic/tasks", tasksToRequeue.size() + " tasks rolled back");
    }
}
