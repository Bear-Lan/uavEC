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

import java.util.*;

import org.redisson.api.RedissonClient;
import org.redisson.api.RMap;

/**
 * 任务服务层
 *
 * 核心职责：
 * - 任务提交与队列管理（基于 Redisson 的分布式优先级队列）
 * - 定时任务调度处理
 * - 节点故障时的任务恢复与迁移
 * - 工作窃取（Work Stealing）机制实现
 *
 * 队列策略：
 * - 高优先级任务（priority >= 4）插入队首，实现"插队"效果
 * - 普通任务追加至队尾
 * - 每周期最多处理 10 个任务
 */
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

    /**
     * 提交任务到调度队列
     *
     * 功能描述：
     * - 为任务生成唯一ID（如果未提供）
     * - 自动生成任务名称（格式：操作员-年月日时分秒-任务ID前4位）
     * - 设置任务提交时间和初始状态为QUEUED
     * - 将任务持久化到MySQL数据库
     * - 创建任务追踪日志（TaskTraceLog）
     * - 根据优先级将任务插入Redisson分布式队列
     *
     * 队列优先级策略：
     * - 高优先级任务（priority >= 4）插入队首，实现"插队"效果
     * - 普通任务追加至队尾
     *
     * @param task 待提交的任务对象
     * @return 任务ID
     */
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
        traceLog.setId(task.getTaskName());
        traceLog.setTaskId(task.getId());
        traceLog.setCreatedTime(System.currentTimeMillis());
        traceLogRepository.save(traceLog);

        // 支持优先级的 Redisson 队列: 具备高优先级 (>=4) 的特权任务直接“插队”到队首 (HEAD)
        org.redisson.api.RDeque<TaskInfo> queue = redissonClient.getDeque(TASK_QUEUE_KEY);
        if (task.getPriority() >= 4) {
            queue.addFirst(task);
            log.info("任务 {} (优先级={}) 以高优先级插入队列头部", task.getTaskName(), task.getPriority());
        } else {
            queue.addLast(task);
            log.info("任务 {} (优先级={}) 加入队列尾部", task.getTaskName(), task.getPriority());
        }
        return task.getId();
    }

    /**
     * 定时处理任务队列
     *
     * 功能描述：
     * - 每500毫秒执行一次（@Scheduled(fixedDelay = 500)）
     * - 每次最多处理10个任务
     * - 从Redisson分布式队列头部取出任务
     * - 更新任务状态为DISPATCHING
     * - 调用DispatchService分发任务到最优节点
     * - 分发失败时将任务重新放回队列头部
     *
     * 追踪日志更新：
     * - 记录出队时间（dequeuedTime）
     * - 计算队列等待延迟（queueLatency）
     */
    @Scheduled(fixedDelay = 500)
    public void processTaskQueue() {
        // 根据在线无人机数量动态调整每周期处理任务数
        // 每个在线节点每周期最多处理2个任务，系统最少处理1个，最多处理20个
        //限制20个任务的上限，防止在节点数量激增时发生雪崩式调度压力
        int onlineNodeCount = (int)nodeService.getAllNodes().stream().filter(com.edg.scheduler.model.UAVNode::isOnline).count();
        int maxPerTick = Math.max(1, Math.min(20, onlineNodeCount * 2));

        org.redisson.api.RDeque<TaskInfo> queue = redissonClient.getDeque(TASK_QUEUE_KEY);

        for (int i = 0; i < maxPerTick; i++) {
            TaskInfo task = queue.pollFirst();
            if (task == null)
                break;

            log.info("从Redis队列取出任务 {}", task.getTaskName());

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
                messagingTemplate.convertAndSend("/topic/tasks", task);
            } else {
                log.warn("任务 {} 分发失败: {}，重新放回队列", task.getTaskName(), result.getMessage());
                // 添加退避延迟，避免连续忙等待
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                task.setStatus("QUEUED");
                task.setAssignedUavId(null);
                taskRepository.save(task);  // 同步数据库状态
                queue.addFirst(task);
                messagingTemplate.convertAndSend("/topic/tasks", task);  // 广播任务状态更新
            }
        }
    }

    @Autowired
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    /**
     * 节点故障恢复 - 重新排队活跃任务
     *
     * 功能描述：
     * - 当节点坠毁（battery耗尽或被手动下线）时调用
     * - 查询该节点上所有处于RUNNING或DISPATCHING状态的任务
     * - 将这些任务状态重置为QUEUED，分配节点置为null
     * - 将任务重新添加到分布式队列
     * - 向前端推送故障恢复通知（FAULT_RECOVERY）
     *
     * 使用场景：
     * - 节点电池耗尽坠毁时触发
     * - 节点被手动下线时触发
     *
     * @param nodeId 故障节点ID
     */
    @Transactional
    public void requeueActiveTasksForNode(String nodeId) {
        log.warn("正在恢复节点 {}. 搜寻活跃任务中...", nodeId);
        java.util.List<TaskInfo> strandedTasks = taskRepository.findByAssignedUavId(nodeId);

        int recovered = 0;
        for (TaskInfo task : strandedTasks) {
            String s = task.getStatus();
            if (s != null && (s.startsWith("RUNNING") || s.equals("DISPATCHING"))) {
                task.setStatus("QUEUED");
                task.setAssignedUavId(null);
                nodeService.release(nodeId, task.getRequiredCpu(), task.getRequiredMemory());
                redissonClient.getMap("task:active").remove(task.getId());
                taskRepository.save(task);

                redissonClient.getDeque(TASK_QUEUE_KEY).addLast(task);
                messagingTemplate.convertAndSend("/topic/tasks", task);  // 广播任务状态更新
                recovered++;
                log.info("从宕机节点 {} 恢复任务 {}", task.getId(), nodeId);
            }
        }
        log.info("节点 {} 恢复完成. {} 个任务已重新排队.", nodeId, recovered);

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
     * RTH返航模式 - 任务主动迁移
     *
     * 功能描述：
     * - 当无人机进入RTH（Return-To-Home）返航模式时调用
     * - 防止任务因无人机返航充电而长时间阻塞超时
     * - 尝试将任务迁移到其他在线且有足够资源的邻居节点
     * - 如果找不到合适的邻居节点，则将任务重排到全局队列并提升为高优先级
     *
     * 迁移策略：
     * - 优先查找有足够CPU和内存的在线邻居节点
     * - 迁移成功时更新资源分配和新节点ID
     * - 迁移失败时将任务设为高优先级重新排队
     *
     * @param nodeId 进入返航模式的节点ID
     */
    @Transactional
    public void migrateTasksFromNode(String nodeId) {
        log.info("RTH返航迁移: 开始为节点 {} 执行主动迁移...", nodeId);
        java.util.List<TaskInfo> activeTasks = taskRepository.findByAssignedUavId(nodeId);

        int migrated = 0;
        int requeued = 0;

        for (TaskInfo task : activeTasks) {
            if ("RUNNING_EDGE".equals(task.getStatus()) || "RUNNING_SPLIT".equals(task.getStatus())) {
                boolean transferSuccess = false;

                // 尝试寻找一个最优邻居节点进行无缝迁移
                for (com.edg.scheduler.model.UAVNode neighbor : nodeService.getAllNodes()) {
                    if (neighbor == null) continue;
                    if (neighbor.isOnline() && !neighbor.isRthMode() && !neighbor.getId().equals(nodeId)) {
                        if (neighbor.getAvailableCpu() >= task.getRequiredCpu() &&
                                neighbor.getAvailableMemory() >= task.getRequiredMemory()) {

                            // 执行模拟迁移：更新资源分配并更改挂载节点
                            nodeService.release(nodeId, task.getRequiredCpu(), task.getRequiredMemory());
                            nodeService.allocate(neighbor.getId(), task.getRequiredCpu(), task.getRequiredMemory());

                            task.setAssignedUavId(neighbor.getId());
                            taskRepository.save(task);

                            // 更新活跃映射以反映新的节点分配
                            redissonClient.getMap("task:active").put(task.getId(), task);

                            log.info("RTH返航迁移: 任务 {} 从 {} 迁移到邻居节点 {}",
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
                    messagingTemplate.convertAndSend("/topic/tasks", task);  // 广播任务状态更新
                    requeued++;
                    log.info("RTH返航迁移: 未找到任务 {} 的邻居节点，强制高优先级重排队.",
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
     * 工作窃取轮询器
     *
     * 功能描述：
     * - 每2秒执行一次（@Scheduled(fixedDelay = 2000)）
     * - 实现负载均衡的Work Stealing算法
     * - 查找空闲节点（activeTasksCount == 0）
     * - 从负载过重的节点（activeTasksCount >= 2）窃取任务
     * - 每个周期最多执行一次工作窃取，防止雪崩
     *
     * 窃取条件：
     * - 源节点：在线、非RTH模式、活跃任务数>=2
     * - 目标节点：在线、非RTH模式、活跃任务数==0
     * - 任务状态：必须为RUNNING_EDGE
     *
     * 窃取后处理：
     * - 更新任务的assignedUavId为空闲节点
     * - 调整源节点和目标节点的资源分配
     * - 更新追踪日志的节点信息
     * - 推送工作窃取通知到前端
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

                                        // 更新活跃映射以反映新的节点分配
                                        redissonClient.getMap("task:active").put(task.getId(), task);

                                        log.info("工作窃取: 闲置节点 {} 从负载节点 {} 窃取任务 {}",
                                                idleNode.getId(), busyNode.getId(), task.getId());

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
                            log.error("工作窃取发生错误", e);
                        }
                    }
                }
            }
        }
    }

    /**
     * 回滚所有活跃任务 - 用于集群快照恢复
     *
     * 功能描述：
     * - 获取Redis中所有活跃任务（task:active）
     * - 释放这些任务在对应节点上占用的资源
     * - 将任务状态重置为QUEUED，清除分配节点
     * - 从活跃映射中移除任务，加入分布式队列
     * - 推送回滚通知到前端
     *
     * 使用场景：
     * - 集群快照回滚（restoreSnapshot）时调用
     * - 确保回滚后节点状态与任务状态一致
     */
    @Transactional
    public void requeueAllActiveTasks() {
        // 不再清空队列，而是扫描数据库中状态异常的任务重新入队
        // 这样可以恢复那些在队列中但因为各种原因丢失的任务

        RMap<String, TaskInfo> activeMap = redissonClient.getMap("task:active");
        List<TaskInfo> allTasks = taskRepository.findAll();

        int requeued = 0;
        Set<String> dbTaskIds = new HashSet<>();

        for (TaskInfo task : allTasks) {
            dbTaskIds.add(task.getId());

            // 处理状态为 RUNNING_* 但不在 activeMap 中的任务（异常任务）
            if (task.getStatus() != null && task.getStatus().startsWith("RUNNING")) {
                if (activeMap.get(task.getId()) == null) {
                    // 任务状态是 RUNNING 但不在活跃映射中，说明已经丢失，重新排队
                    // 先释放节点资源
                    if (task.getAssignedUavId() != null) {
                        nodeService.release(task.getAssignedUavId(), task.getRequiredCpu(), task.getRequiredMemory());
                    }
                    task.setStatus("QUEUED");
                    task.setAssignedUavId(null);
                    taskRepository.save(task);

                    redissonClient.getDeque(TASK_QUEUE_KEY).addLast(task);
                    messagingTemplate.convertAndSend("/topic/tasks", task);
                    requeued++;
                    log.info("Task {} recovered from orphaned state", task.getId());
                }
            }
        }

        // 清理 task:active 中已不存在于数据库的任务（僵尸条目）
        Set<String> redisTaskIds = activeMap.keySet();
        int cleaned = 0;
        for (String redisTaskId : redisTaskIds) {
            if (!dbTaskIds.contains(redisTaskId)) {
                activeMap.remove(redisTaskId);
                cleaned++;
                log.info("Cleaned stale task {} from task:active", redisTaskId);
            }
        }

        if (requeued > 0) {
            log.info("Recovered {} orphaned tasks", requeued);
        }
        if (cleaned > 0) {
            log.info("Cleaned {} stale entries from task:active", cleaned);
        }
    }
}
