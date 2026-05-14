package com.edg.scheduler.controller;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.model.TaskTraceLog;
import com.edg.scheduler.model.Operator;
import com.edg.scheduler.model.BatchMetricsSummary;
import com.edg.scheduler.service.NodeService;
import com.edg.scheduler.service.TaskService;
import com.edg.scheduler.repository.TaskTraceLogRepository;
import com.edg.scheduler.repository.OperatorRepository;
import com.edg.scheduler.repository.BatchMetricsSummaryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.management.ManagementFactory;

/**
 * 应用主控制器
 *
 * 提供系统核心功能 API：
 *
 * 任务管理 (/api/tasks/*):
 * - GET /tasks/completed - 获取已完成任务列表
 * - POST /tasks - 提交单个任务
 * - POST /tasks/batch - 批量提交任务
 * - GET /tasks/{taskId}/trace - 获取任务追踪日志
 *
 * 节点管理 (/api/nodes/*):
 * - GET /nodes - 获取所有节点
 * - POST /nodes/add - 添加新节点
 * - DELETE /nodes/{id} - 删除节点
 * - POST /nodes/{id}/status - 设置节点在线/离线状态
 * - POST /nodes/{id}/charge - 紧急充电
 * - POST /nodes/{id}/position - 设置节点位置（手动锚定）
 * - POST /nodes/snapshot - 创建集群快照
 * - POST /nodes/rollback - 回滚集群状态
 * - PUT /nodes/{id}/config - 更新节点配置
 *
 * 流量生成 (/api/traffic/*):
 * - POST /traffic/start - 启动泊松流量生成
 * - POST /traffic/stop - 停止流量生成
 * - GET /traffic/status - 获取流量生成状态
 *
 * 指标查询 (/api/metrics/*):
 * - GET /metrics/{batchId} - 获取批次性能指标
 * - GET /metrics/history - 获取历史性能指标
 * - GET /metrics/export - 导出指标为 CSV
 *
 * 系统状态 (/api/system/*):
 * - GET /system/status - 获取系统状态
 *
 * 初始化时自动创建默认管理员账户
 */
@RestController
@RequestMapping("/api")
public class AppController {

    @Autowired
    private TaskService taskService;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private com.edg.scheduler.repository.TaskRepository taskRepository;

    @Autowired
    private TaskTraceLogRepository traceLogRepository;

    @Autowired
    private OperatorRepository operatorRepository;

    @Autowired
    private BatchMetricsSummaryRepository batchMetricsSummaryRepository;

    @Autowired
    private com.edg.scheduler.service.TrafficGeneratorService trafficGeneratorService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PostConstruct
    public void initAdmin() {
        if (operatorRepository.count() == 0) {
            // 生成随机默认密码并打印，首次登录后建议修改
            String defaultPassword = java.util.UUID.randomUUID().toString().substring(0, 8);
            Operator admin = new Operator("admin", passwordEncoder.encode(defaultPassword), 50, 50);
            admin.setRole("ADMIN");
            operatorRepository.save(admin);
            System.out.println(">>> Initialized default admin user: admin / " + defaultPassword + " (please change after first login)");
        }
    }

    // ==================== Tasks ====================

    @GetMapping("/tasks/completed")
    public ResponseEntity<List<TaskInfo>> getCompletedTasks() {
        List<TaskInfo> tasks = taskRepository.findAll().stream()
                .filter(t -> t.getStatus() != null
                        && (t.getStatus().startsWith("COMPLETED") || t.getStatus().equals("FAILED")))
                .sorted((a, b) -> Long.compare(b.getSubmitTime(), a.getSubmitTime()))
                .limit(100)
                .toList();
        return ResponseEntity.ok(tasks);
    }

    @PostMapping("/tasks")
    public ResponseEntity<?> submitTask(@RequestBody TaskInfo task) {
        String taskId = taskService.submitTask(task);
        return ResponseEntity.ok(Map.of("taskId", taskId, "message", "Task submitted successfully"));
    }

    @PostMapping("/tasks/batch")
    public ResponseEntity<List<String>> submitBatchTasks(@RequestBody List<TaskInfo> tasks) {
        List<String> taskIds = new ArrayList<>();
        for (TaskInfo task : tasks) {
            taskIds.add(taskService.submitTask(task));
        }
        return ResponseEntity.ok(taskIds);
    }

    // ==================== Nodes ====================

    @PostMapping("/nodes/{id}/status")
    public ResponseEntity<Void> setNodeStatus(@PathVariable String id, @RequestParam boolean online) {
        nodeService.setNodeStatus(id, online);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/nodes/{id}/charge")
    public ResponseEntity<Void> emergencyCharge(@PathVariable String id) {
        nodeService.emergencyCharge(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/nodes")
    public ResponseEntity<List<UAVNode>> getNodes() {
        return ResponseEntity.ok(nodeService.getAllNodes());
    }

    @PostMapping("/nodes/add")
    public ResponseEntity<UAVNode> addNode() {
        return ResponseEntity.ok(nodeService.addNode());
    }

    @DeleteMapping("/nodes/{id}")
    public ResponseEntity<Void> deleteNode(@PathVariable String id) {
        if (nodeService.deleteNode(id)) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // ==================== Traffic Generator ====================

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

    @PostMapping("/traffic/stop")
    public ResponseEntity<Void> stopTrafficGeneration() {
        trafficGeneratorService.stopGeneration();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/traffic/status")
    public ResponseEntity<Map<String, Object>> getTrafficStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("active", trafficGeneratorService.isStatusGenerating());
        status.put("lambda", trafficGeneratorService.getLambda());
        return ResponseEntity.ok(status);
    }

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

    @PostMapping("/nodes/snapshot")
    public ResponseEntity<Void> createSnapshot() {
        nodeService.createSnapshot();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/nodes/rollback")
    public ResponseEntity<Void> rollbackSnapshot() {
        nodeService.restoreSnapshot();
        return ResponseEntity.ok().build();
    }

    // ==================== Metrics ====================

    @GetMapping("/metrics/{batchId}")
    public ResponseEntity<Map<String, Object>> getBatchMetrics(@PathVariable String batchId) {
        List<TaskInfo> tasks = taskRepository.findByBatchId(batchId);

        if (tasks.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        int totalTasks = tasks.size();
        int completedTasks = 0;

        long totalLatencyMs = 0;
        double totalEnergyJ = 0;
        double totalBandwidthMB = 0;

        for (TaskInfo task : tasks) {
            String status = task.getStatus();
            if (status != null && status.startsWith("COMPLETED")) {
                completedTasks++;
                if (task.getEndTime() > task.getSubmitTime()) {
                    totalLatencyMs += (task.getEndTime() - task.getSubmitTime());
                }
                totalEnergyJ += task.getActualEnergyUsed();
                totalBandwidthMB += task.getDataSize();
            } else if (status != null && status.equals("FAILED")) {
                totalEnergyJ += task.getActualEnergyUsed();
                totalBandwidthMB += task.getDataSize();
            }
        }

        boolean allFinalized = tasks.stream()
                .allMatch(t -> {
                    String s = t.getStatus();
                    return s != null && (s.startsWith("COMPLETED") || s.equals("FAILED"));
                });

        Map<String, Object> response = new HashMap<>();
        if (!allFinalized) {
            response.put("status", "PROCESSING");
            long running = tasks.stream().filter(t -> {
                String s = t.getStatus();
                return s != null && (s.startsWith("RUNNING") || s.startsWith("PENDING") || s.equals("QUEUED")
                        || s.equals("DISPATCHING"));
            }).count();
            response.put("runningCount", running);
            return ResponseEntity.ok(response);
        }

        double successRate = (totalTasks > 0) ? ((double) completedTasks / totalTasks) * 100.0 : 0.0;
        double avgLatency = (completedTasks > 0) ? (double) totalLatencyMs / completedTasks : 0.0;

        double avgLatencySec = avgLatency / 1000.0;
        double apparentBandwidth = (avgLatencySec > 0) ? (totalBandwidthMB / avgLatencySec) : 0.0;

        response.put("status", "FINISHED");
        response.put("batchId", batchId);
        response.put("latency", Math.round(avgLatency));
        response.put("energy", Math.round(totalEnergyJ));
        response.put("bandwidth", Math.round(apparentBandwidth));
        response.put("success", Math.round(successRate));

        // 截取日志聚合性能指标指标落库，供后续复盘溯源使用
        // 从批次切片中反向推导使用的调度算法策略
        String batchAlg = tasks.stream()
                .map(TaskInfo::getSchedulingAlgorithm)
                .filter(a -> a != null && !a.isBlank())
                .findFirst().orElse("unknown");
        if (!batchMetricsSummaryRepository.existsByBatchId(batchId)) {
            BatchMetricsSummary summary = new BatchMetricsSummary();
            summary.setBatchId(batchId);
            summary.setAlgorithm(batchAlg);
            summary.setLatency(Math.round(avgLatency));
            summary.setEnergy(Math.round(totalEnergyJ));
            summary.setBandwidth(Math.round(apparentBandwidth));
            summary.setSuccessRate(Math.round(successRate));
            batchMetricsSummaryRepository.save(summary);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/metrics/history")
    public ResponseEntity<List<BatchMetricsSummary>> getMetricsHistory() {
        return ResponseEntity.ok(batchMetricsSummaryRepository.findAllByOrderByCreatedAtAsc());
    }

    // ==================== Tracing ====================

    @GetMapping("/tasks/{taskId}/trace")
    public ResponseEntity<TaskTraceLog> getTaskTraceLog(@PathVariable String taskId) {
        TaskTraceLog log = traceLogRepository.findByTaskId(taskId);
        if (log != null) {
            return ResponseEntity.ok(log);
        }
        return ResponseEntity.notFound().build();
    }
    // --- System Meta APIs ---

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

    // ==================== CSV Data Export ====================

    @GetMapping("/metrics/export")
    public ResponseEntity<String> exportMetricsCsv(
            @RequestParam(required = false) String batchId) {

        List<TaskInfo> tasks;
        if (batchId != null && !batchId.isBlank()) {
            tasks = taskRepository.findByBatchId(batchId);
        } else {
            // If no batchId, export ALL finalized tasks (limited to 2000 most recent)
            tasks = taskRepository.findAll().stream()
                    .filter(t -> t.getStatus() != null
                            && (t.getStatus().startsWith("COMPLETED") || t.getStatus().equals("FAILED")))
                    .sorted((a, b) -> Long.compare(b.getSubmitTime(), a.getSubmitTime()))
                    .limit(2000)
                    .toList();
        }

        StringBuilder csv = new StringBuilder();
        // CSV Header
        csv.append(
                "taskId,batchId,algorithm,taskType,dataSizeMB,requiredCpu,latencyMs,energyJ,assignedNode,status,submitTime\n");

        for (TaskInfo t : tasks) {
            long latencyMs = (t.getEndTime() > 0 && t.getSubmitTime() > 0)
                    ? (t.getEndTime() - t.getSubmitTime())
                    : 0;
            double energy = t.getActualEnergyUsed();

            csv.append(String.format("%s,%s,%s,%s,%.0f,%.1f,%d,%.2f,%s,%s,%s\n",
                    nullSafe(t.getId()),
                    nullSafe(t.getBatchId()),
                    nullSafe(t.getSchedulingAlgorithm()),
                    nullSafe(t.getType()),
                    t.getDataSize(),
                    t.getRequiredCpu(),
                    latencyMs,
                    energy,
                    nullSafe(t.getAssignedUavId()),
                    nullSafe(t.getStatus()),
                    t.getSubmitTime() > 0 ? new java.util.Date(t.getSubmitTime()).toString() : ""));
        }

        String filename = batchId != null
                ? "uav_metrics_" + batchId + ".csv"
                : "uav_metrics_all.csv";

        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(csv.toString());
    }

    private String nullSafe(String value) {
        return value != null ? value.replace(",", ";").replace("\n", "") : "";
    }
}
