package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 贪心调度算法单元测试
 * 测试 GreedyAlgorithm 选择 CPU 剩余最多的节点的逻辑
 */
class GreedyAlgorithmTest {

    private GreedyAlgorithm greedyAlgorithm;

    @BeforeEach
    void setUp() {
        greedyAlgorithm = new GreedyAlgorithm();
    }

    @Test
    void testGetName() {
        assertEquals("greedy", greedyAlgorithm.getName());
    }

    @Test
    void testSelectNode_WithMultipleNodes_SelectsMaxCpu() {
        // 准备测试数据：3个节点，CPU剩余分别为 8, 15, 10
        // node1: maxCpu=10, usage=2, available=8
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node1.setCurrentCpuUsage(2);  // available = 10-2 = 8

        // node2: maxCpu=20, usage=5, available=15 (最多)
        UAVNode node2 = new UAVNode("node-2", "UAV-2", 20, 100, 100);
        node2.setCurrentCpuUsage(5);  // available = 20-5 = 15

        // node3: maxCpu=15, usage=5, available=10
        UAVNode node3 = new UAVNode("node-3", "UAV-3", 15, 100, 100);
        node3.setCurrentCpuUsage(5);  // available = 15-5 = 10

        List<UAVNode> nodes = Arrays.asList(node1, node2, node3);

        TaskInfo task = createTask(2, 512);

        UAVNode selected = greedyAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-2", selected.getId());
    }

    @Test
    void testSelectNode_WithEmptyList_ReturnsNull() {
        List<UAVNode> emptyNodes = Arrays.asList();
        TaskInfo task = createTask(2, 512);

        UAVNode selected = greedyAlgorithm.selectNode(emptyNodes, task);

        assertNull(selected);
    }

    @Test
    void testSelectNode_WithSingleNode_ReturnsThatNode() {
        UAVNode node = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node.setCurrentCpuUsage(5);
        List<UAVNode> nodes = Arrays.asList(node);

        TaskInfo task = createTask(2, 512);

        UAVNode selected = greedyAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-1", selected.getId());
    }

    @Test
    void testSelectNode_AllNodesHaveSameCpu_ReturnsFirst() {
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node1.setCurrentCpuUsage(5);
        UAVNode node2 = new UAVNode("node-2", "UAV-2", 10, 100, 100);
        node2.setCurrentCpuUsage(5);
        UAVNode node3 = new UAVNode("node-3", "UAV-3", 10, 100, 100);
        node3.setCurrentCpuUsage(5);
        List<UAVNode> nodes = Arrays.asList(node1, node2, node3);

        TaskInfo task = createTask(2, 512);

        UAVNode selected = greedyAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        // 当 CPU 相等时，返回第一个（stream().max 的行为）
        assertEquals("node-1", selected.getId());
    }

    /**
     * 辅助方法：创建测试用 TaskInfo
     */
    private TaskInfo createTask(int requiredCpu, int requiredMemory) {
        TaskInfo task = new TaskInfo();
        task.setId("task-" + System.currentTimeMillis());
        task.setType("IMAGE_PROCESSING");
        task.setRequiredCpu(requiredCpu);
        task.setRequiredMemory(requiredMemory);
        task.setPriority(3);
        task.setDataSize(50);
        return task;
    }
}