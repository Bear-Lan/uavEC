package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调度算法综合性能对比测试
 * 在相同测试环境下对 Greedy、WFQ、Geo 三种算法进行控制变量测试
 */
class AlgorithmPerformanceTest {

    private static final int TASK_COUNT = 100;  // 每个算法测试100个任务
    private static final int NODE_COUNT = 10;   // 10个无人机节点

    private Random random;

    @BeforeEach
    void setUp() {
        random = new Random(42);  // 固定种子保证可复现
    }

    /**
     * 生成随机任务
     */
    private TaskInfo createRandomTask(String taskId) {
        TaskInfo task = new TaskInfo();
        task.setId(taskId);
        task.setTaskName("TASK-" + taskId);
        task.setType(randomTaskType());
        task.setDataSize(10 + random.nextDouble() * 90);  // 10-100 MB
        task.setRequiredCpu(1 + random.nextDouble() * 3);  // 1-4 cores
        task.setRequiredMemory(256 + random.nextDouble() * 768);  // 256-1024 MB
        task.setPriority(1 + random.nextInt(5));
        task.setOriginX(random.nextDouble() * 100);
        task.setOriginY(random.nextDouble() * 100);
        return task;
    }

    private String randomTaskType() {
        String[] types = {"IMAGE_PROCESSING", "DATA_ANALYSIS", "SENSOR_FUSION", "PATH_PLANNING"};
        return types[random.nextInt(types.length)];
    }

    /**
     * 生成随机节点集群
     */
    private List<UAVNode> createNodeCluster() {
        List<UAVNode> nodes = new ArrayList<>();
        String[] profiles = {"HEAVY_GPU", "BALANCED", "SOLAR_SCOUT", "MICRO_SENSOR"};
        for (int i = 0; i < NODE_COUNT; i++) {
            UAVNode node = new UAVNode(
                    "uav-" + i,
                    "UAV-" + i,
                    8 + random.nextDouble() * 8  // 8-16 cores
            );
            node.setMaxMemory((8 + random.nextDouble() * 8) * 2048);
            node.setNetworkBandwidth(50 + random.nextDouble() * 50);
            node.setX(random.nextDouble() * 100);
            node.setY(random.nextDouble() * 100);
            node.setBattery(30 + random.nextDouble() * 70);
            node.setHardwareProfile(profiles[random.nextInt(profiles.length)]);
            node.setDrainRateMultiplier(0.8 + random.nextDouble() * 0.4);
            node.setSolarHarvesting(random.nextBoolean());
            node.setOnline(true);
            node.setActiveTasksCount(new AtomicInteger(random.nextInt(5)));
            nodes.add(node);
        }
        return nodes;
    }

    @Test
    void testAlgorithmPerformanceComparison() {
        System.out.println("========================================");
        System.out.println("   调度算法综合性能对比测试");
        System.out.println("========================================");
        System.out.println("测试配置: " + TASK_COUNT + " 任务, " + NODE_COUNT + " 节点");
        System.out.println();

        // 创建共享的测试数据
        List<TaskInfo> tasks = new ArrayList<>();
        for (int i = 0; i < TASK_COUNT; i++) {
            tasks.add(createRandomTask("task-" + i));
        }

        List<UAVNode> nodesGreedy = createNodeCluster();
        List<UAVNode> nodesWfq = deepCopyNodes(nodesGreedy);
        List<UAVNode> nodesGeo = deepCopyNodes(nodesGreedy);

        // 测试 Greedy 算法
        GreedyAlgorithm greedy = new GreedyAlgorithm();
        PerformanceResult greedyResult = runAlgorithm(greedy, nodesGreedy, tasks, "Greedy");

        // 重置节点状态
        nodesWfq.forEach(n -> n.setActiveTasksCount(new AtomicInteger(0)));
        nodesWfq.forEach(n -> n.setCurrentCpuUsage(0));
        nodesWfq.forEach(n -> n.setCurrentMemoryUsage(0));

        // 测试 WFQ 算法
        WfqAlgorithm wfq = new WfqAlgorithm();
        PerformanceResult wfqResult = runAlgorithm(wfq, nodesWfq, tasks, "WFQ");

        // 重置节点状态
        nodesGeo.forEach(n -> n.setActiveTasksCount(new AtomicInteger(0)));
        nodesGeo.forEach(n -> n.setCurrentCpuUsage(0));
        nodesGeo.forEach(n -> n.setCurrentMemoryUsage(0));

        // 测试 Geo 算法
        GeoAlgorithm geo = new GeoAlgorithm();
        PerformanceResult geoResult = runAlgorithm(geo, nodesGeo, tasks, "Geo");

        // 输出对比结果
        printComparison(greedyResult, wfqResult, geoResult);
    }

    private List<UAVNode> deepCopyNodes(List<UAVNode> original) {
        return original.stream().map(UAVNode::new).collect(Collectors.toList());
    }

    private PerformanceResult runAlgorithm(SchedulingAlgorithm algo, List<UAVNode> nodes,
                                           List<TaskInfo> tasks, String algoName) {
        PerformanceResult result = new PerformanceResult(algoName);
        Map<String, UAVNode> nodeMap = nodes.stream()
                .collect(Collectors.toMap(UAVNode::getId, n -> n));

        for (TaskInfo task : tasks) {
            UAVNode selected = algo.selectNode(nodes, task);

            if (selected != null) {
                result.tasksAssigned++;

                // 模拟任务执行
                double distance = calculateDistance(selected.getX(), selected.getY(),
                        task.getOriginX(), task.getOriginY());

                // 计算延迟
                long queueLatency = (long) (distance * 5);  // 距离相关延迟
                long txLatency = (long) (task.getDataSize() / selected.getNetworkBandwidth() * 8 * 10);
                long computeLatency = (long) ((task.getDataSize() / selected.getMaxCpu()) * 100);

                long totalLatency = queueLatency + txLatency + computeLatency;
                result.totalLatency += totalLatency;
                result.maxLatency = Math.max(result.maxLatency, totalLatency);
                result.minLatency = Math.min(result.minLatency, totalLatency);

                // 累计能耗
                double computingPower = 5.0;  // W
                double txPower = 2.0 * Math.pow(distance / 10, 2);  // 距离相关发射功耗
                double timeInSec = totalLatency / 1000.0;
                double energy = (computingPower + txPower) * timeInSec;
                result.totalEnergy += energy;

                // 带宽利用
                double bandwidthUsed = task.getDataSize() / timeInSec;
                result.totalBandwidth += bandwidthUsed;

                // 更新节点状态
                selected.getActiveTasksCount().incrementAndGet();
                selected.setCurrentCpuUsage(selected.getCurrentCpuUsage() + task.getRequiredCpu());
                selected.setCurrentMemoryUsage(selected.getCurrentMemoryUsage() + task.getRequiredMemory());
            } else {
                result.tasksFailed++;
            }
        }

        result.successRate = (result.tasksAssigned * 100.0) / tasks.size();
        result.avgLatency = result.totalLatency / Math.max(1, result.tasksAssigned);
        result.avgBandwidth = result.totalBandwidth / Math.max(1, result.tasksAssigned);

        return result;
    }

    private double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    private void printComparison(PerformanceResult greedy, PerformanceResult wfq, PerformanceResult geo) {
        System.out.println();
        System.out.println("========================================");
        System.out.println("           测试结果汇总");
        System.out.println("========================================");
        System.out.println();
        System.out.println("┌──────────────┬────────────┬────────────┬────────────┐");
        System.out.println("│    指标      │   Greedy   │    WFQ     │    Geo     │");
        System.out.println("├──────────────┼────────────┼────────────┼────────────┤");
        System.out.printf("│ 平均延迟(ms) │  %9.0f  │  %9.0f  │  %9.0f  │%n",
                greedy.avgLatency, wfq.avgLatency, geo.avgLatency);
        System.out.printf("│ 总能耗(J)    │  %9.0f  │  %9.0f  │  %9.0f  │%n",
                greedy.totalEnergy, wfq.totalEnergy, geo.totalEnergy);
        System.out.printf("│ 平均带宽     │  %9.1f  │  %9.1f  │  %9.1f  │%n",
                greedy.avgBandwidth, wfq.avgBandwidth, geo.avgBandwidth);
        System.out.printf("│ 成功率(%%)    │  %9.1f  │  %9.1f  │  %9.1f  │%n",
                greedy.successRate, wfq.successRate, geo.successRate);
        System.out.println("└──────────────┴────────────┴────────────┴────────────┘");
        System.out.println();

        // 详细分析
        System.out.println("【数据分析】");
        System.out.println();

        String bestLatency = greedy.avgLatency <= wfq.avgLatency && greedy.avgLatency <= geo.avgLatency ? "Greedy" :
                (wfq.avgLatency <= geo.avgLatency ? "WFQ" : "Geo");
        String bestEnergy = greedy.totalEnergy <= wfq.totalEnergy && greedy.totalEnergy <= geo.totalEnergy ? "Greedy" :
                (wfq.totalEnergy <= geo.totalEnergy ? "WFQ" : "Geo");
        String bestBandwidth = greedy.avgBandwidth >= wfq.avgBandwidth && greedy.avgBandwidth >= geo.avgBandwidth ? "Greedy" :
                (wfq.avgBandwidth >= geo.avgBandwidth ? "WFQ" : "Geo");
        String bestSuccess = greedy.successRate >= wfq.successRate && greedy.successRate >= geo.successRate ? "Greedy" :
                (wfq.successRate >= geo.successRate ? "WFQ" : "Geo");

        System.out.println("- 最低延迟: " + bestLatency + " 算法 (" +
                String.format("%.0f", minOf(greedy.avgLatency, wfq.avgLatency, geo.avgLatency)) + " ms)");
        System.out.println("- 最省能耗: " + bestEnergy + " 算法 (" +
                String.format("%.0f", minOf(greedy.totalEnergy, wfq.totalEnergy, geo.totalEnergy)) + " J)");
        System.out.println("- 最高带宽利用率: " + bestBandwidth + " 算法 (" +
                String.format("%.1f", maxOf(greedy.avgBandwidth, wfq.avgBandwidth, geo.avgBandwidth)) + " MB/s)");
        System.out.println("- 最高成功率: " + bestSuccess + " 算法 (" +
                String.format("%.1f", maxOf(greedy.successRate, wfq.successRate, geo.successRate)) + "%)");
        System.out.println();

        // Geo算法优势分析
        System.out.println("【Geo算法优势分析】");
        if (geo.avgLatency <= greedy.avgLatency && geo.avgLatency <= wfq.avgLatency) {
            System.out.println("✓ Geo算法在延迟指标上表现最优");
        }
        if (geo.totalEnergy <= greedy.totalEnergy && geo.totalEnergy <= wfq.totalEnergy) {
            System.out.println("✓ Geo算法在能耗指标上表现最优");
        }
        if (geo.avgBandwidth >= greedy.avgBandwidth && geo.avgBandwidth >= wfq.avgBandwidth) {
            System.out.println("✓ Geo算法在带宽利用率上表现最优");
        }
    }

    private double minOf(double a, double b, double c) {
        return Math.min(Math.min(a, b), c);
    }

    private double maxOf(double a, double b, double c) {
        return Math.max(Math.max(a, b), c);
    }

    static class PerformanceResult {
        String algorithm;
        long totalLatency = 0;
        long maxLatency = Long.MIN_VALUE;
        long minLatency = Long.MAX_VALUE;
        double totalEnergy = 0;
        double totalBandwidth = 0;
        double avgLatency = 0;
        double avgBandwidth = 0;
        double successRate = 0;
        int tasksAssigned = 0;
        int tasksFailed = 0;

        PerformanceResult(String algorithm) {
            this.algorithm = algorithm;
        }
    }
}