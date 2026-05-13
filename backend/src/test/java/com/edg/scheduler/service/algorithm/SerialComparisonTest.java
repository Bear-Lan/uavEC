package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 串行对比测试：100个任务，数据载荷50MB，所需内存64MB，所需CPU 2核
 * 覆盖 Greedy、WFQ、Geo 三种算法
 *
 * 模拟真实调度流程：allocate() → 执行 → release()
 */
class SerialComparisonTest {

    private static final int TASK_COUNT = 100;
    private static final double DATA_SIZE_MB = 50.0;
    private static final int REQUIRED_MEMORY_MB = 64;
    private static final double REQUIRED_CPU_CORES = 2.0;

    private List<TaskInfo> tasks;
    private List<UAVNode> nodeCluster;

    @BeforeEach
    void setUp() {
        // 创建100个完全相同的任务
        tasks = new ArrayList<>();
        for (int i = 0; i < TASK_COUNT; i++) {
            TaskInfo task = new TaskInfo();
            task.setId("task-" + i);
            task.setTaskName("TASK-" + i);
            task.setType("IMAGE_PROCESSING");
            task.setDataSize(DATA_SIZE_MB);
            task.setRequiredCpu(REQUIRED_CPU_CORES);
            task.setRequiredMemory(REQUIRED_MEMORY_MB);
            task.setPriority(3);
            task.setOriginX(50.0);  // 统一任务来源为中心点
            task.setOriginY(50.0);
            tasks.add(task);
        }

        // 创建10个无人机节点，每个8核
        nodeCluster = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UAVNode node = new UAVNode(
                    "uav-" + i,
                    "UAV-" + i,
                    8.0  // 8 cores each
            );
            node.setMaxMemory(2048.0);  // 2GB memory per node
            node.setNetworkBandwidth(100.0);
            node.setX(10.0 + i * 10.0);  // 均匀分布在 10~100
            node.setY(50.0);  // 全部在 Y=50 的横线上
            node.setBattery(100.0);
            node.setOnline(true);
            node.setActiveTasksCount(new AtomicInteger(0));
            nodeCluster.add(node);
        }
    }

    /**
     * 模拟 allocate 逻辑：分配资源到节点
     */
    private boolean allocateNode(UAVNode node, double cpu, double memory) {
        if (node.isOnline() && node.getBattery() > 5.0) {
            node.setCurrentCpuUsage(node.getCurrentCpuUsage() + cpu);
            node.setCurrentMemoryUsage(node.getCurrentMemoryUsage() + memory);
            node.getActiveTasksCount().incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * 模拟 release 逻辑：释放资源
     */
    private void releaseNode(UAVNode node, double cpu, double memory) {
        node.setCurrentCpuUsage(Math.max(0, node.getCurrentCpuUsage() - cpu));
        node.setCurrentMemoryUsage(Math.max(0, node.getCurrentMemoryUsage() - memory));
        int newCount = node.getActiveTasksCount().decrementAndGet();
        if (newCount < 0) {
            node.getActiveTasksCount().set(0);
        }
    }

    @Test
    void testGreedyAlgorithm_100Tasks_Serial() {
        System.out.println("=== Greedy Algorithm Test ===");
        List<UAVNode> nodes = deepCopyCluster();

        int successCount = 0;
        int failCount = 0;

        for (TaskInfo task : tasks) {
            UAVNode selected = new GreedyAlgorithm().selectNode(nodes, task);

            if (selected != null && selected.getAvailableCpu() >= task.getRequiredCpu()
                    && selected.getAvailableMemory() >= task.getRequiredMemory()) {
                // 模拟真实调度流程：allocate → 执行 → release
                if (allocateNode(selected, task.getRequiredCpu(), task.getRequiredMemory())) {
                    successCount++;
                    // 串行执行：任务完成后立即释放资源
                    releaseNode(selected, task.getRequiredCpu(), task.getRequiredMemory());
                } else {
                    failCount++;
                }
            } else {
                failCount++;
            }
        }

        System.out.println("Greedy Results:");
        System.out.println("  Success: " + successCount + "/" + TASK_COUNT);
        System.out.println("  Failed: " + failCount + "/" + TASK_COUNT);

        assertEquals(TASK_COUNT, successCount, "Greedy: All 100 tasks should succeed");
    }

    @Test
    void testWfqAlgorithm_100Tasks_Serial() {
        System.out.println("\n=== WFQ Algorithm Test ===");
        List<UAVNode> nodes = deepCopyCluster();

        int successCount = 0;
        int failCount = 0;

        for (TaskInfo task : tasks) {
            UAVNode selected = new WfqAlgorithm().selectNode(nodes, task);

            if (selected != null && selected.getAvailableCpu() >= task.getRequiredCpu()
                    && selected.getAvailableMemory() >= task.getRequiredMemory()) {
                if (allocateNode(selected, task.getRequiredCpu(), task.getRequiredMemory())) {
                    successCount++;
                    releaseNode(selected, task.getRequiredCpu(), task.getRequiredMemory());
                } else {
                    failCount++;
                }
            } else {
                failCount++;
            }
        }

        System.out.println("WFQ Results:");
        System.out.println("  Success: " + successCount + "/" + TASK_COUNT);
        System.out.println("  Failed: " + failCount + "/" + TASK_COUNT);

        assertEquals(TASK_COUNT, successCount, "WFQ: All 100 tasks should succeed");
    }

    @Test
    void testGeoAlgorithm_100Tasks_Serial() {
        System.out.println("\n=== Geo Algorithm Test ===");
        List<UAVNode> nodes = deepCopyCluster();

        int successCount = 0;
        int failCount = 0;

        for (TaskInfo task : tasks) {
            UAVNode selected = new GeoAlgorithm().selectNode(nodes, task);

            if (selected != null && selected.getAvailableCpu() >= task.getRequiredCpu()
                    && selected.getAvailableMemory() >= task.getRequiredMemory()) {
                if (allocateNode(selected, task.getRequiredCpu(), task.getRequiredMemory())) {
                    successCount++;
                    releaseNode(selected, task.getRequiredCpu(), task.getRequiredMemory());
                } else {
                    failCount++;
                }
            } else {
                failCount++;
            }
        }

        System.out.println("Geo Results:");
        System.out.println("  Success: " + successCount + "/" + TASK_COUNT);
        System.out.println("  Failed: " + failCount + "/" + TASK_COUNT);

        assertEquals(TASK_COUNT, successCount, "Geo: All 100 tasks should succeed");
    }

    @Test
    void testAllAlgorithms_CompareResults() {
        System.out.println("\n=== Algorithm Comparison ===");

        int greedySuccess = runAlgorithm(new GreedyAlgorithm(), deepCopyCluster());
        int wfqSuccess = runAlgorithm(new WfqAlgorithm(), deepCopyCluster());
        int geoSuccess = runAlgorithm(new GeoAlgorithm(), deepCopyCluster());

        System.out.println("\nFinal Comparison:");
        System.out.println("  Greedy: " + greedySuccess + "/" + TASK_COUNT);
        System.out.println("  WFQ:    " + wfqSuccess + "/" + TASK_COUNT);
        System.out.println("  Geo:    " + geoSuccess + "/" + TASK_COUNT);

        assertEquals(TASK_COUNT, greedySuccess, "Greedy should handle all 100 tasks");
        assertEquals(TASK_COUNT, wfqSuccess, "WFQ should handle all 100 tasks");
        assertEquals(TASK_COUNT, geoSuccess, "Geo should handle all 100 tasks");
    }

    private int runAlgorithm(SchedulingAlgorithm algorithm, List<UAVNode> nodes) {
        int count = 0;
        for (TaskInfo task : tasks) {
            UAVNode selected = algorithm.selectNode(nodes, task);
            if (selected != null && selected.getAvailableCpu() >= task.getRequiredCpu()
                    && selected.getAvailableMemory() >= task.getRequiredMemory()) {
                if (allocateNode(selected, task.getRequiredCpu(), task.getRequiredMemory())) {
                    count++;
                    releaseNode(selected, task.getRequiredCpu(), task.getRequiredMemory());
                }
            }
        }
        return count;
    }

    private List<UAVNode> deepCopyCluster() {
        List<UAVNode> copy = new ArrayList<>();
        for (UAVNode node : nodeCluster) {
            UAVNode cloned = new UAVNode(node);
            cloned.setActiveTasksCount(new AtomicInteger(0));
            cloned.setCurrentCpuUsage(0.0);
            cloned.setCurrentMemoryUsage(0.0);
            copy.add(cloned);
        }
        return copy;
    }
}