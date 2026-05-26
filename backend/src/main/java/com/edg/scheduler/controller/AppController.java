package com.edg.scheduler.controller;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.model.TaskTraceLog;
import com.edg.scheduler.model.BatchMetricsSummary;
import com.edg.scheduler.service.AuthService;
import com.edg.scheduler.service.MetricsService;
import com.edg.scheduler.service.NodeService;
import com.edg.scheduler.service.TaskService;
import com.edg.scheduler.service.TrafficGeneratorService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.management.ManagementFactory;

/**
 * 应用主控制器
 *
 * 提供系统级API接口，包含以下功能模块：
 * - 任务管理：任务提交、批量提交、已完成任务查询
 * - 节点管理：节点状态控制、位置设置、快照与回滚
 * - 流量控制：泊松流量生成器的启动/停止
 * - 指标查询：批次指标、历史指标、CSV导出
 * - 系统状态：运行时间、节点统计
 */
@RestController
@RequestMapping("/api")
public class AppController {

    @Autowired
    private TaskService taskService;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private MetricsService metricsService;

    @Autowired
    private TrafficGeneratorService trafficGeneratorService;

    @Autowired
    private AuthService authService;

    @Autowired(required = false)
    private com.edg.scheduler.service.offloading.DQNOfflineTrainer dqnOfflineTrainer;

    @Autowired(required = false)
    private com.edg.scheduler.service.offloading.strategy.DQNOffloadingStrategy dqnOffloadingStrategy;

    /**
     * 初始化默认管理员账户
     *
     * 功能描述：
     * - @PostConstruct生命周期钩子
     * - 应用启动时检查是否需要创建默认admin账户
     * - 如果是首次启动，生成随机密码并创建admin用户
     * - 打印初始化信息到控制台
     */
    @PostConstruct
    public void initAdmin() {
        String defaultPassword = authService.initializeDefaultAdminIfNeeded();
        if (defaultPassword != null) {
            System.out.println(">>> Initialized default admin user: admin / " + defaultPassword + " (please change after first login)");
        }
    }

    /**
     * 获取已完成的任务列表
     *
     * @return 最近100条已完成任务（按提交时间倒序）
     */
    @GetMapping("/tasks/completed")
    public ResponseEntity<List<TaskInfo>> getCompletedTasks() {
        return ResponseEntity.ok(metricsService.getCompletedTasks(100));
    }

    /**
     * 获取所有活跃/排队任务
     *
     * @return 当前正在执行或排队的任务列表
     */
    @GetMapping("/tasks/active")
    public ResponseEntity<List<TaskInfo>> getActiveTasks() {
        return ResponseEntity.ok(metricsService.getActiveTasks());
    }

    /**
     * 提交单个任务
     *
     * @param task 任务信息（JSON格式）
     * @return 任务ID和提交成功消息
     */
    @PostMapping("/tasks")
    public ResponseEntity<?> submitTask(@RequestBody TaskInfo task) {
        String taskId = taskService.submitTask(task);
        return ResponseEntity.ok(Map.of("taskId", taskId, "message", "Task submitted successfully"));
    }

    /**
     * 批量提交任务
     *
     * @param tasks 任务列表（JSON数组格式）
     * @return 提交的任务ID列表
     */
    @PostMapping("/tasks/batch")
    public ResponseEntity<List<String>> submitBatchTasks(@RequestBody List<TaskInfo> tasks) {
        List<String> taskIds = new ArrayList<>();
        for (TaskInfo task : tasks) {
            taskIds.add(taskService.submitTask(task));
        }
        return ResponseEntity.ok(taskIds);
    }

    /**
     * 设置节点在线/离线状态（故障注入）
     *
     * @param id 节点ID
     * @param online 是否在线
     * @return 无
     */
    @PostMapping("/nodes/{id}/status")
    public ResponseEntity<Void> setNodeStatus(@PathVariable String id, @RequestParam boolean online) {
        nodeService.setNodeStatus(id, online);
        return ResponseEntity.ok().build();
    }

    /**
     * 紧急充电节点
     *
     * @param id 节点ID
     * @return 无
     */
    @PostMapping("/nodes/{id}/charge")
    public ResponseEntity<Void> emergencyCharge(@PathVariable String id) {
        nodeService.emergencyCharge(id);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取所有节点列表
     *
     * @return 节点列表
     */
    @GetMapping("/nodes")
    public ResponseEntity<List<UAVNode>> getNodes() {
        return ResponseEntity.ok(nodeService.getAllNodes());
    }

    /**
     * 动态添加新节点
     *
     * @return 新创建的节点
     */
    @PostMapping("/nodes/add")
    public ResponseEntity<UAVNode> addNode() {
        return ResponseEntity.ok(nodeService.addNode());
    }

    /**
     * 删除指定节点
     *
     * @param id 节点ID
     * @return 成功返回200，节点不存在返回404
     */
    @DeleteMapping("/nodes/{id}")
    public ResponseEntity<Void> deleteNode(@PathVariable String id) {
        if (nodeService.deleteNode(id)) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 启动泊松流量生成
     *
     * 请求体参数：
     * - lambda: 每秒任务数（默认5.0）
     * - algorithm: 调度算法（默认geo）
     * - originX/originY: 任务源坐标（默认50.0, 50.0）
     * - customW1/W2/W3: 自定义权重（默认0.5）
     *
     * @param req 请求体（JSON格式）
     * @return 无
     */
    @PostMapping("/traffic/start")
    public ResponseEntity<Void> startTrafficGeneration(@RequestBody Map<String, Object> req) {
        double lambda = req.containsKey("lambda") ? ((Number) req.get("lambda")).doubleValue() : 5.0;
        String algorithm = (String) req.getOrDefault("algorithm", "geo");
        double originX = req.containsKey("originX") ? ((Number) req.get("originX")).doubleValue() : 50.0;
        double originY = req.containsKey("originY") ? ((Number) req.get("originY")).doubleValue() : 50.0;
        double w1 = req.containsKey("customW1") ? ((Number) req.get("customW1")).doubleValue() : 0.5;
        double w2 = req.containsKey("customW2") ? ((Number) req.get("customW2")).doubleValue() : 0.5;
        double w3 = req.containsKey("customW3") ? ((Number) req.get("customW3")).doubleValue() : 0.5;

        trafficGeneratorService.startGeneration(lambda, algorithm, originX, originY, w1, w2, w3);
        return ResponseEntity.ok().build();
    }

    /**
     * 停止泊松流量生成
     *
     * @return 无
     */
    @PostMapping("/traffic/stop")
    public ResponseEntity<Void> stopTrafficGeneration() {
        trafficGeneratorService.stopGeneration();
        return ResponseEntity.ok().build();
    }

    /**
     * 获取流量生成状态
     *
     * @return 状态对象（包含active和lambda字段）
     */
    @GetMapping("/traffic/status")
    public ResponseEntity<Map<String, Object>> getTrafficStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("active", trafficGeneratorService.isStatusGenerating());
        status.put("lambda", trafficGeneratorService.getLambda());
        return ResponseEntity.ok(status);
    }

    /**
     * 设置节点位置（手动控制）
     *
     * 功能描述：
     * - 手动设置节点X,Y坐标
     * - 设置manualOverride为true，节点将停止自动巡航
     *
     * @param id 节点ID
     * @param x X坐标
     * @param y Y坐标
     * @return 成功返回200，节点不存在返回404
     */
    @PostMapping("/nodes/{id}/position")
    public ResponseEntity<Void> setNodePosition(@PathVariable String id, @RequestParam double x,
            @RequestParam double y) {
        UAVNode node = nodeService.getNode(id);
        if (node != null) {
            node.setX(x);
            node.setY(y);
            node.setManualOverride(true);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 创建集群快照
     *
     * 用于混沌工程测试，支持后续回滚
     *
     * @return 无
     */
    @PostMapping("/nodes/snapshot")
    public ResponseEntity<Void> createSnapshot() {
        nodeService.createSnapshot();
        return ResponseEntity.ok().build();
    }

    /**
     * 从快照回滚集群状态
     *
     * @return 无
     */
    @PostMapping("/nodes/rollback")
    public ResponseEntity<Void> rollbackSnapshot() {
        nodeService.restoreSnapshot();
        return ResponseEntity.ok().build();
    }

    /**
     * 获取指定批次的性能指标
     *
     * @param batchId 批次ID
     * @return 性能指标（包含status、latency、energy、bandwidth、success）
     *         如果批次不存在返回404
     */
    @GetMapping("/metrics/{batchId}")
    public ResponseEntity<Map<String, Object>> getBatchMetrics(@PathVariable String batchId) {
        Map<String, Object> result = metricsService.getBatchMetrics(batchId);
        if ("NOT_FOUND".equals(result.get("status"))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定批次的详细分析数据（支持单次批次即时分析）
     *
     * @param batchId 批次ID
     * @return 批次详情分析（包含任务列表、节点分布、类型分布、进度时间线、分位数等）
     *         如果批次不存在返回404
     */
    @GetMapping("/metrics/{batchId}/detail")
    public ResponseEntity<Map<String, Object>> getBatchDetailAnalytics(@PathVariable String batchId) {
        Map<String, Object> result = metricsService.getBatchDetailAnalytics(batchId);
        if ("NOT_FOUND".equals(result.get("status"))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取历史性能指标
     *
     * @return 所有历史批次指标汇总列表
     */
    @GetMapping("/metrics/history")
    public ResponseEntity<List<BatchMetricsSummary>> getMetricsHistory() {
        // 先同步所有批次汇总数据（确保新完成的批次也被记录）
        metricsService.syncAllBatchMetrics();
        return ResponseEntity.ok(metricsService.getMetricsHistory());
    }

    /**
     * 手动同步所有批次的性能指标汇总
     * 解决单次批次提交后历史图表无数据的问题
     *
     * @return 同步了多少个批次
     */
    @PostMapping("/metrics/sync")
    public ResponseEntity<Map<String, Object>> syncAllBatchMetrics() {
        int synced = metricsService.syncAllBatchMetrics();
        return ResponseEntity.ok(Map.of("synced", synced, "message", "成功同步 " + synced + " 个批次汇总记录"));
    }

    /**
     * 获取任务追踪日志
     *
     * @param taskId 任务ID
     * @return 任务追踪日志（包含队列延迟、传输延迟、计算延迟）
     *         如果任务不存在返回404
     */
    @GetMapping("/tasks/{taskId}/trace")
    public ResponseEntity<TaskTraceLog> getTaskTraceLog(@PathVariable String taskId) {
        TaskTraceLog log = metricsService.getTaskTraceLog(taskId);
        if (log != null) {
            return ResponseEntity.ok(log);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 获取系统运行状态
     *
     * @return 系统状态（包含运行时间、在线节点数、总节点数、版本）
     */
    @GetMapping("/system/status")
    public ResponseEntity<?> getSystemStatus() {
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        int onlineNodes = (int) nodeService.getAllNodes().stream().filter(UAVNode::isOnline).count();
        int totalNodes = nodeService.getAllNodes().size();

        Map<String, Object> status = new HashMap<>();
        status.put("uptimeMs", uptime);
        status.put("onlineNodes", onlineNodes);
        status.put("totalNodes", totalNodes);
        status.put("version", "1.1.0-CYBER");

        return ResponseEntity.ok(status);
    }

    /**
     * 导出性能指标为CSV格式
     *
     * @param batchId 批次ID（可选，为空则导出所有已完成任务）
     * @return CSV格式文本文件
     */
    @GetMapping("/metrics/export")
    public ResponseEntity<String> exportMetricsCsv(@RequestParam(required = false) String batchId) {
        String csv = metricsService.exportMetricsCsv(batchId);
        String filename = batchId != null
                ? "uav_metrics_" + batchId + ".csv"
                : "uav_metrics_all.csv";

        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    // ==================== DQN 离线训练接口 ====================

    /**
     * 执行 DQN 离线训练
     *
     * 从数据库读取已完成任务的真实执行记录（延迟、能耗），
     * 利用真实 reward 替代估算 reward 进行训练，
     * 训练完成后自动重新加载最新权重。
     *
     * @param batchId 可选，指定批次ID进行针对性训练；为空则训练所有历史数据
     * @return 训练结果摘要
     */
    @PostMapping("/dqn/train")
    public ResponseEntity<Map<String, Object>> trainDQN(
            @RequestParam(required = false) String batchId) {
        if (dqnOfflineTrainer == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "UNAVAILABLE",
                    "message", "DQN离线训练服务未启用"));
        }
        if (dqnOfflineTrainer.isTraining()) {
            return ResponseEntity.ok(Map.of(
                    "status", "SKIP",
                    "message", "训练正在进行中，请稍后"));
        }

        Map<String, Object> result = dqnOfflineTrainer.trainOffline(batchId);

        // 训练成功后，重新加载最新权重
        if ("SUCCESS".equals(result.get("status")) && dqnOffloadingStrategy != null) {
            dqnOffloadingStrategy.reloadWeightsFromFile();
            result.put("weightsReloaded", true);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取 DQN 训练状态
     *
     * @return 训练状态信息（是否训练中、离线经验数、总训练次数）
     */
    @GetMapping("/dqn/status")
    public ResponseEntity<Map<String, Object>> getDQNStatus() {
        if (dqnOfflineTrainer == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "UNAVAILABLE",
                    "message", "DQN离线训练服务未启用"));
        }

        return ResponseEntity.ok(Map.of(
                "isTraining", dqnOfflineTrainer.isTraining(),
                "offlineBufferSize", dqnOfflineTrainer.getOfflineBufferSize(),
                "totalTrainCount", dqnOfflineTrainer.getTotalTrainCount(),
                "message", String.format("离线经验数: %d, 训练次数: %d",
                        dqnOfflineTrainer.getOfflineBufferSize(),
                        dqnOfflineTrainer.getTotalTrainCount())));
    }

    /**
     * 手动触发保存当前 DQN 权重到文件
     *
     * @return 保存结果
     */
    @PostMapping("/dqn/save")
    public ResponseEntity<Map<String, Object>> saveDQNWeights() {
        if (dqnOffloadingStrategy == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "UNAVAILABLE",
                    "message", "DQN策略未启用"));
        }
        try {
            java.lang.reflect.Method persistMethod =
                    dqnOffloadingStrategy.getClass().getDeclaredMethod("persistWeights");
            persistMethod.setAccessible(true);
            persistMethod.invoke(dqnOffloadingStrategy);
            return ResponseEntity.ok(Map.of(
                    "status", "SAVED",
                    "message", "权重已保存到 data/dqn/dqn_weights.json"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "ERROR",
                    "message", "保存失败: " + e.getMessage()));
        }
    }

    /**
     * 从文件重新加载 DQN 权重
     *
     * @return 重新加载结果
     */
    @PostMapping("/dqn/reload")
    public ResponseEntity<Map<String, Object>> reloadDQNWeights() {
        if (dqnOffloadingStrategy == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "UNAVAILABLE",
                    "message", "DQN策略未启用"));
        }
        try {
            java.lang.reflect.Method reloadMethod =
                    dqnOffloadingStrategy.getClass().getDeclaredMethod("reloadWeightsFromFile");
            reloadMethod.setAccessible(true);
            reloadMethod.invoke(dqnOffloadingStrategy);
            return ResponseEntity.ok(Map.of(
                    "status", "RELOADED",
                    "message", "权重已从文件重新加载"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "ERROR",
                    "message", "重新加载失败: " + e.getMessage()));
        }
    }
}