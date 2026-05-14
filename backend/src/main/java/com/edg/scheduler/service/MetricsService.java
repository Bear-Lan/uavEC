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
            return response;
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