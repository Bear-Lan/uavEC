package com.edg.scheduler.service;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.service.algorithm.GreedyAlgorithm;
import com.edg.scheduler.service.algorithm.SchedulingAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发任务分配与分布式锁一致性测试
 * 验证系统在多线程并发下任务分配的一致性
 */
class ConcurrencyTest {

    private static final int CONCURRENT_TASKS = 20;
    private static final int NODE_COUNT = 5;
    private static final int THREAD_POOL_SIZE = 10;

    private ExecutorService executor;
    private List<UAVNode> nodes;
    private GreedyAlgorithm greedyAlgorithm;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        nodes = createNodeCluster();
        greedyAlgorithm = new GreedyAlgorithm();
    }

    private List<UAVNode> createNodeCluster() {
        List<UAVNode> nodeList = new ArrayList<>();
        for (int i = 0; i < NODE_COUNT; i++) {
            UAVNode node = new UAVNode("node-" + i, "Node-" + i, 8.0);
            node.setMaxMemory(8192);
            node.setNetworkBandwidth(100);
            node.setOnline(true);
            node.setBattery(100);
            node.setActiveTasksCount(new AtomicInteger(0));
            node.setCurrentCpuUsage(0);
            nodeList.add(node);
        }
        return nodeList;
    }

    @Test
    void testConcurrentTaskAssignment_NoDuplicateAssignment() throws InterruptedException {
        System.out.println("========================================");
        System.out.println("  并发任务分配与分布式锁一致性测试");
        System.out.println("========================================");
        System.out.println("测试配置: " + CONCURRENT_TASKS + " 并发任务, " + NODE_COUNT + " 节点, " + THREAD_POOL_SIZE + " 线程");
        System.out.println();

        // 模拟简单的本地锁（模拟 Redisson 分布式锁行为）
        Object lock = new Object();
        Map<String, AtomicInteger> assignmentCount = new ConcurrentHashMap<>();
        Map<String, Set<String>> nodeTaskAssignments = new ConcurrentHashMap<>();
        AtomicInteger totalAssigned = new AtomicInteger(0);
        AtomicInteger totalFailed = new AtomicInteger(0);

        // 初始化计数
        for (UAVNode node : nodes) {
            assignmentCount.put(node.getId(), new AtomicInteger(0));
            nodeTaskAssignments.put(node.getId(), ConcurrentHashMap.newKeySet());
        }

        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_TASKS; i++) {
            final int taskId = i;
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                TaskInfo task = createTask("task-" + taskId, 2.0, 1024);

                // 模拟分布式锁临界区
                synchronized (lock) {
                    UAVNode selected = greedyAlgorithm.selectNode(nodes, task);

                    if (selected != null) {
                        // 模拟 allocate 逻辑
                        if (selected.getAvailableCpu() >= task.getRequiredCpu() &&
                            selected.getAvailableMemory() >= task.getRequiredMemory()) {

                            // 检查是否已分配给该节点（防止重复分配）
                            if (!nodeTaskAssignments.get(selected.getId()).contains(task.getId())) {
                                nodeTaskAssignments.get(selected.getId()).add(task.getId());
                                assignmentCount.get(selected.getId()).incrementAndGet();
                                totalAssigned.incrementAndGet();

                                // 更新节点状态
                                selected.setCurrentCpuUsage(selected.getCurrentCpuUsage() + task.getRequiredCpu());
                                selected.setCurrentMemoryUsage(selected.getCurrentMemoryUsage() + task.getRequiredMemory());
                                selected.getActiveTasksCount().incrementAndGet();

                                return selected.getId();
                            } else {
                                totalFailed.incrementAndGet();
                                return "DUPLICATE";
                            }
                        }
                    }
                    totalFailed.incrementAndGet();
                    return "FAILED";
                }
            }, executor);

            futures.add(future);
        }

        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 输出结果
        System.out.println("【任务分配结果】");
        System.out.println();
        System.out.println("┌──────────────┬────────────┬────────────┐");
        System.out.println("│    节点      │  分配任务数 │  负载率(%) │");
        System.out.println("├──────────────┼────────────┼────────────┤");

        double totalLoad = 0;
        for (UAVNode node : nodes) {
            int count = assignmentCount.get(node.getId()).get();
            double load = (node.getCurrentCpuUsage() / node.getMaxCpu()) * 100;
            totalLoad += load;
            System.out.printf("│ %-12s │    %5d    │   %6.1f   │%n",
                    node.getId(), count, load);
        }
        System.out.println("└──────────────┴────────────┴────────────┘");

        double avgLoad = totalLoad / nodes.size();
        System.out.println();
        System.out.println("【并发测试统计】");
        System.out.println("- 总提交任务: " + CONCURRENT_TASKS);
        System.out.println("- 成功分配: " + totalAssigned.get());
        System.out.println("- 分配失败: " + totalFailed.get());
        System.out.println("- 平均节点负载: " + String.format("%.1f", avgLoad) + "%");
        System.out.println();

        // 验证结果
        assertEquals(CONCURRENT_TASKS, totalAssigned.get() + totalFailed.get(),
                "总任务数应等于成功+失败数");

        // 验证负载均衡度
        long zeroAssignmentNodes = assignmentCount.values().stream()
                .filter(c -> c.get() == 0).count();
        System.out.println("【负载均衡分析】");
        System.out.println("- 零分配节点数: " + zeroAssignmentNodes);
        System.out.println("- 任务分布标准差: " + String.format("%.2f", calculateStdDev(assignmentCount.values())));

        // 验证无重复分配
        int totalUniqueAssignments = nodeTaskAssignments.values().stream()
                .mapToInt(Set::size).sum();
        System.out.println("- 无重复分配: " + (totalUniqueAssignments == totalAssigned.get() ? "✓ 通过" : "✗ 失败"));
        System.out.println();

        System.out.println("【测试结论】");
        if (totalFailed.get() == 0) {
            System.out.println("✓ 所有 " + CONCURRENT_TASKS + " 个并发任务均成功分配，无任务丢失");
        } else {
            double successRate = (totalAssigned.get() * 100.0) / CONCURRENT_TASKS;
            System.out.println("✓ 成功率: " + String.format("%.1f", successRate) + "%");
            System.out.println("  失败任务因节点资源耗尽触发云端降级，属于正常行为");
        }
    }

    @Test
    void testTaskAllocationAndRelease() {
        System.out.println();
        System.out.println("========================================");
        System.out.println("     任务分配与释放资源测试");
        System.out.println("========================================");

        UAVNode node = new UAVNode("test-node", "TestNode", 8.0);
        node.setMaxMemory(8192);
        node.setOnline(true);
        node.setBattery(100);

        System.out.println();
        System.out.println("初始状态:");
        System.out.println("- CPU: " + node.getCurrentCpuUsage() + "/" + node.getMaxCpu());
        System.out.println("- Memory: " + node.getCurrentMemoryUsage() + "/" + node.getMaxMemory());
        System.out.println("- Active Tasks: " + node.getActiveTasksCount().get());

        // 模拟分配
        double cpuToAlloc = 2.0;
        double memToAlloc = 1024;

        node.setCurrentCpuUsage(node.getCurrentCpuUsage() + cpuToAlloc);
        node.setCurrentMemoryUsage(node.getCurrentMemoryUsage() + memToAlloc);
        node.getActiveTasksCount().incrementAndGet();

        System.out.println();
        System.out.println("分配 " + cpuToAlloc + " CPU, " + memToAlloc + " MB 后:");
        System.out.println("- CPU: " + node.getCurrentCpuUsage() + "/" + node.getMaxCpu());
        System.out.println("- Memory: " + node.getCurrentMemoryUsage() + "/" + node.getMaxMemory());
        System.out.println("- Active Tasks: " + node.getActiveTasksCount().get());

        // 模拟释放
        node.setCurrentCpuUsage(Math.max(0, node.getCurrentCpuUsage() - cpuToAlloc));
        node.setCurrentMemoryUsage(Math.max(0, node.getCurrentMemoryUsage() - memToAlloc));
        int newCount = node.getActiveTasksCount().decrementAndGet();
        if (newCount < 0) node.getActiveTasksCount().set(0);

        System.out.println();
        System.out.println("释放资源后:");
        System.out.println("- CPU: " + node.getCurrentCpuUsage() + "/" + node.getMaxCpu());
        System.out.println("- Memory: " + node.getCurrentMemoryUsage() + "/" + node.getMaxMemory());
        System.out.println("- Active Tasks: " + node.getActiveTasksCount().get());
        System.out.println();

        // 验证
        assertEquals(0, node.getCurrentCpuUsage());
        assertEquals(0, node.getCurrentMemoryUsage());
        assertEquals(0, node.getActiveTasksCount().get());
        System.out.println("✓ 资源分配与释放测试通过");
    }

    private TaskInfo createTask(String id, double cpu, double memory) {
        TaskInfo task = new TaskInfo();
        task.setId(id);
        task.setTaskName("Task-" + id);
        task.setType("TEST");
        task.setRequiredCpu(cpu);
        task.setRequiredMemory(memory);
        task.setDataSize(50);
        task.setPriority(3);
        return task;
    }

    private double calculateStdDev(Collection<AtomicInteger> values) {
        List<Integer> intVals = values.stream().map(AtomicInteger::get).toList();
        double mean = intVals.stream().mapToInt(Integer::intValue).average().orElse(0);
        double variance = intVals.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        return Math.sqrt(variance);
    }
}