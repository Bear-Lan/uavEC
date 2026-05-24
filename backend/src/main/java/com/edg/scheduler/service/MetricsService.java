package com.edg.scheduler.service;

import com.edg.scheduler.model.BatchMetricsSummary;
import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.TaskTraceLog;
import com.edg.scheduler.repository.BatchMetricsSummaryRepository;
import com.edg.scheduler.repository.TaskRepository;
import com.edg.scheduler.repository.TaskTraceLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标服务
 *
 * 核心职责：
 * - 已完成任务查询：获取最近N条已完成任务
 * - 批次指标查询：获取指定批次的性能指标
 * - 指标历史查询：获取所有历史批次指标
 * - 任务追踪日志：获取任务的完整执行流水
 * - CSV导出：导出指标数据为CSV格式
 *
 * 事务策略：
 * - getCompletedTasks: @Transactional(readOnly=true)
 * - getBatchMetrics: @Transactional(readOnly=true)
 * - getMetricsHistory: @Transactional(readOnly=true)
 * - exportMetricsCsv: @Transactional(readOnly=true)
 */
@Slf4j
@Service
public class MetricsService {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskTraceLogRepository traceLogRepository;

    @Autowired
    private BatchMetricsSummaryRepository batchMetricsSummaryRepository;

    /**
     * 获取已完成的任务列表
     * @param limit 返回数量上限（默认 100）
     * @return 已完成任务列表（按提交时间倒序）
     */
    @Transactional(readOnly = true)
    public List<TaskInfo> getCompletedTasks(int limit) {
        return taskRepository.findAll().stream()
                .filter(t -> t.getStatus() != null
                        && (t.getStatus().startsWith("COMPLETED") || t.getStatus().equals("FAILED")))
                .sorted((a, b) -> Long.compare(b.getSubmitTime(), a.getSubmitTime()))
                .limit(limit > 0 ? limit : 100)
                .toList();
    }

    /**
     * 获取所有活跃/排队任务
     * @return 当前正在执行或排队的任务列表
     */
    @Transactional(readOnly = true)
    public List<TaskInfo> getActiveTasks() {
        return taskRepository.findAll().stream()
                .filter(t -> t.getStatus() != null
                        && (t.getStatus().startsWith("RUNNING")
                            || t.getStatus().equals("DISPATCHING")
                            || t.getStatus().equals("QUEUED")))
                .toList();
    }

    /**
     * 获取指定批次的性能指标
     * @param batchId 批次 ID
     * @return 性能指标 Map，包含：
     *         - status: "PROCESSING"（进行中）、"FINISHED"（已完成）、"NOT_FOUND"（未找到）
     *         - batchId、latency、energy、bandwidth、success（FINISHED 时包含）
     *         - runningCount（PROCESSING 时包含）
     */
    @Transactional
    public Map<String, Object> getBatchMetrics(String batchId) {
        List<TaskInfo> tasks = taskRepository.findByBatchId(batchId);

        if (tasks.isEmpty()) {
            return Map.of("status", "NOT_FOUND");
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
                    return s != null && (s.startsWith("COMPLETED") || s.equals("FAILED") || s.equals("PENDING"));
                });

        Map<String, Object> response = new HashMap<>();
        if (!allFinalized) {
            response.put("status", "PROCESSING");
            response.put("totalTasks", totalTasks);
            response.put("completedTasks", completedTasks);
            long running = tasks.stream().filter(t -> {
                String s = t.getStatus();
                return s != null && (s.startsWith("RUNNING") || s.startsWith("PENDING") || s.equals("QUEUED")
                        || s.equals("DISPATCHING"));
            }).count();
            response.put("runningCount", running);
            return response;
        }

        double successRate = (totalTasks > 0) ? ((double) completedTasks / totalTasks) * 100.0 : 0.0;
        double avgLatency = (completedTasks > 0) ? (double) totalLatencyMs / completedTasks : 0.0;

        double avgLatencySec = avgLatency / 1000.0;
        double apparentBandwidth = (avgLatencySec > 0) ? (totalBandwidthMB / avgLatencySec) : 0.0;

        response.put("status", "FINISHED");
        response.put("batchId", batchId);
        response.put("totalTasks", totalTasks);
        response.put("completedTasks", completedTasks);
        response.put("latency", Math.round(avgLatency));
        response.put("energy", Math.round(totalEnergyJ));
        response.put("bandwidth", Math.round(apparentBandwidth));
        response.put("success", Math.round(successRate));

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
            summary.setTaskCount(totalTasks);
            batchMetricsSummaryRepository.save(summary);
        }

        return response;
    }

    /**
     * 获取指定批次的详细分析数据（支持单次批次即时分析）
     * @param batchId 批次 ID
     * @return 包含以下信息的 Map：
     *         - status: PROCESSING / FINISHED / NOT_FOUND
     *         - totalTasks, completedTasks, failedTasks
     *         - taskList: 任务详情列表（包含 latency, energy, nodeId, status, type）
     *         - nodeDistribution: 各节点任务分布
     *         - typeDistribution: 任务类型分布
     *         - progressTimeline: 完成进度时间线（每5秒一个节点）
     *         - aggregate: 聚合指标（avgLatency, totalEnergy, successRate）
     *         - percentile: 延迟分位数（p50, p90, p99）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getBatchDetailAnalytics(String batchId) {
        List<TaskInfo> tasks = taskRepository.findByBatchId(batchId);

        if (tasks.isEmpty()) {
            return Map.of("status", "NOT_FOUND");
        }

        int totalTasks = tasks.size();
        int completedTasks = 0;
        int failedTasks = 0;
        long totalLatencyMs = 0;
        double totalEnergyJ = 0;

        List<Map<String, Object>> taskList = new java.util.ArrayList<>();
        Map<String, Integer> nodeDist = new HashMap<>();
        Map<String, Integer> typeDist = new HashMap<>();
        List<Long> latencyList = new java.util.ArrayList<>();

        for (TaskInfo task : tasks) {
            String status = task.getStatus();
            if (status != null && status.startsWith("COMPLETED")) {
                completedTasks++;
                long latency = task.getEndTime() > task.getSubmitTime()
                        ? task.getEndTime() - task.getSubmitTime() : 0;
                totalLatencyMs += latency;
                latencyList.add(latency);
                totalEnergyJ += task.getActualEnergyUsed();
            } else if (status != null && status.equals("FAILED")) {
                failedTasks++;
                totalEnergyJ += task.getActualEnergyUsed();
            }

            // 节点分布
            String nodeId = task.getAssignedUavId() != null ? task.getAssignedUavId() : "UNASSIGNED";
            nodeDist.put(nodeId, nodeDist.getOrDefault(nodeId, 0) + 1);

            // 类型分布
            String type = task.getType() != null ? task.getType() : "UNKNOWN";
            typeDist.put(type, typeDist.getOrDefault(type, 0) + 1);

            // 任务详情
            Map<String, Object> taskDetail = new HashMap<>();
            taskDetail.put("id", task.getId());
            taskDetail.put("name", task.getTaskName());
            taskDetail.put("status", status);
            taskDetail.put("type", type);
            taskDetail.put("nodeId", nodeId);
            taskDetail.put("priority", task.getPriority());
            taskDetail.put("dataSize", task.getDataSize());
            taskDetail.put("cpu", task.getRequiredCpu());
            taskDetail.put("memory", task.getRequiredMemory());
            if (task.getEndTime() > task.getSubmitTime()) {
                taskDetail.put("latency", task.getEndTime() - task.getSubmitTime());
            } else {
                taskDetail.put("latency", 0L);
            }
            taskDetail.put("energy", task.getActualEnergyUsed());
            taskDetail.put("submitTime", task.getSubmitTime());
            taskDetail.put("startTime", task.getStartTime());
            taskDetail.put("endTime", task.getEndTime());
            taskList.add(taskDetail);
        }

        // 计算分位数
        Map<String, Long> percentile = new HashMap<>();
        if (!latencyList.isEmpty()) {
            java.util.Collections.sort(latencyList);
            int p50Idx = (int) Math.ceil(latencyList.size() * 0.50) - 1;
            int p90Idx = (int) Math.ceil(latencyList.size() * 0.90) - 1;
            int p99Idx = (int) Math.ceil(latencyList.size() * 0.99) - 1;
            percentile.put("p50", latencyList.get(Math.max(0, p50Idx)));
            percentile.put("p90", latencyList.get(Math.max(0, p90Idx)));
            percentile.put("p99", latencyList.get(Math.max(0, p99Idx)));
        }

        // 进度时间线（以最早提交时间为起点，按5秒间隔分段）
        Map<String, Object> response = new HashMap<>();
        boolean allFinalized = tasks.stream().allMatch(t -> {
            String s = t.getStatus();
            return s != null && (s.startsWith("COMPLETED") || s.equals("FAILED"));
        });
        response.put("status", allFinalized ? "FINISHED" : "PROCESSING");
        response.put("totalTasks", totalTasks);
        response.put("completedTasks", completedTasks);
        response.put("failedTasks", failedTasks);
        response.put("taskList", taskList);
        response.put("nodeDistribution", nodeDist);
        response.put("typeDistribution", typeDist);
        response.put("percentile", percentile);
        response.put("aggregate", Map.of(
                "avgLatency", completedTasks > 0 ? totalLatencyMs / completedTasks : 0L,
                "totalEnergy", Math.round(totalEnergyJ),
                "successRate", totalTasks > 0 ? ((double) completedTasks / totalTasks) * 100.0 : 0.0,
                "throughput", completedTasks > 0 && !latencyList.isEmpty()
                        ? (double) completedTasks / (totalLatencyMs / 1000.0 / completedTasks) : 0.0
        ));

        return response;
    }

    /**
     * 获取历史性能指标
     * @return 所有性能指标汇总列表（按创建时间升序）
     */
    @Transactional(readOnly = true)
    public List<BatchMetricsSummary> getMetricsHistory() {
        return batchMetricsSummaryRepository.findAllByOrderByCreatedAtAsc();
    }

    /**
     * 同步所有批次的性能指标汇总
     * 扫描 task_info 表中所有已完成批次，为没有汇总记录的批次自动创建汇总
     * 解决单次批次提交后历史图表无数据的问题
     */
    @Transactional
    public int syncAllBatchMetrics() {
        // 查找所有有 batchId 且所有任务都已完成（COMPLETED/FAILED）的批次
        List<TaskInfo> allTasks = taskRepository.findAll();
        Map<String, List<TaskInfo>> batchTasks = new HashMap<>();
        for (TaskInfo task : allTasks) {
            if (task.getBatchId() != null && !task.getBatchId().isBlank()) {
                batchTasks.computeIfAbsent(task.getBatchId(), k -> new java.util.ArrayList<>()).add(task);
            }
        }

        int synced = 0;
        for (Map.Entry<String, List<TaskInfo>> entry : batchTasks.entrySet()) {
            String batchId = entry.getKey();
            List<TaskInfo> tasks = entry.getValue();

            // 检查是否所有任务都已最终状态
            boolean allFinalized = tasks.stream().allMatch(t -> {
                String s = t.getStatus();
                return s != null && (s.startsWith("COMPLETED") || s.equals("FAILED"));
            });

            if (!allFinalized) continue; // 跳过仍在处理中的批次

            if (batchMetricsSummaryRepository.existsByBatchId(batchId)) continue; // 已有汇总

            int totalTasks = tasks.size();
            int completedTasks = 0;
            int failedTasks = 0;
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
                    failedTasks++;
                    totalEnergyJ += task.getActualEnergyUsed();
                    totalBandwidthMB += task.getDataSize();
                }
            }

            double successRate = totalTasks > 0 ? ((double) completedTasks / totalTasks) * 100.0 : 0.0;
            double avgLatency = completedTasks > 0 ? (double) totalLatencyMs / completedTasks : 0.0;
            double avgLatencySec = avgLatency / 1000.0;
            double apparentBandwidth = avgLatencySec > 0 ? (totalBandwidthMB / avgLatencySec) : 0.0;

            String batchAlg = tasks.stream()
                    .map(TaskInfo::getSchedulingAlgorithm)
                    .filter(a -> a != null && !a.isBlank())
                    .findFirst().orElse("unknown");

            BatchMetricsSummary summary = new BatchMetricsSummary();
            summary.setBatchId(batchId);
            summary.setAlgorithm(batchAlg);
            summary.setLatency(Math.round(avgLatency));
            summary.setEnergy(Math.round(totalEnergyJ));
            summary.setBandwidth(Math.round(apparentBandwidth));
            summary.setSuccessRate(Math.round(successRate));
            summary.setTaskCount(totalTasks);
            batchMetricsSummaryRepository.save(summary);
            synced++;
            log.info("同步批次汇总: batchId={}, 算法={}, 任务数={}, 成功率={}%",
                    batchId, batchAlg, totalTasks, String.format("%.1f", successRate));
        }
        return synced;
    }

    /**
     * 获取指定任务的追踪日志
     * @param taskId 任务 ID
     * @return 任务追踪日志（如果存在）
     */
    @Transactional(readOnly = true)
    public TaskTraceLog getTaskTraceLog(String taskId) {
        return traceLogRepository.findByTaskId(taskId);
    }

    /**
     * 导出性能指标为 CSV 格式
     * @param batchId 批次 ID（可选，为空则导出所有已完成任务）
     * @return CSV 格式的字符串
     */
    @Transactional(readOnly = true)
    public String exportMetricsCsv(String batchId) {
        List<TaskInfo> tasks;
        if (batchId != null && !batchId.isBlank()) {
            tasks = taskRepository.findByBatchId(batchId);
        } else {
            tasks = taskRepository.findAll().stream()
                    .filter(t -> t.getStatus() != null
                            && (t.getStatus().startsWith("COMPLETED") || t.getStatus().equals("FAILED")))
                    .sorted((a, b) -> Long.compare(b.getSubmitTime(), a.getSubmitTime()))
                    .limit(2000)
                    .toList();
        }

        StringBuilder csv = new StringBuilder();
        csv.append("taskId,batchId,algorithm,taskType,dataSizeMB,requiredCpu,latencyMs,energyJ,assignedNode,status,submitTime\n");

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
        return csv.toString();
    }

    private String nullSafe(String value) {
        return value != null ? value.replace(",", ";").replace("\n", "") : "";
    }
}