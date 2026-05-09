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
 * 加权公平队列调度算法单元测试
 * 测试 WfqAlgorithm 选择任务数最少的节点的逻辑（公平调度）
 */
class WfqAlgorithmTest {

    private WfqAlgorithm wfqAlgorithm;

    @BeforeEach
    void setUp() {
        wfqAlgorithm = new WfqAlgorithm();
    }

    @Test
    void testGetName() {
        assertEquals("wfq", wfqAlgorithm.getName());
    }

    @Test
    void testSelectNode_WithMultipleNodes_SelectsMinTasks() {
        // 准备测试数据：3个节点，活跃任务数分别为 5, 2, 8
        // node1 有 5 个任务
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node1.setActiveTasksCount(new AtomicInteger(5));

        // node2 只有 2 个任务（最少，应该被选中）
        UAVNode node2 = new UAVNode("node-2", "UAV-2", 10, 100, 100);
        node2.setActiveTasksCount(new AtomicInteger(2));

        // node3 有 8 个任务
        UAVNode node3 = new UAVNode("node-3", "UAV-3", 10, 100, 100);
        node3.setActiveTasksCount(new AtomicInteger(8));

        List<UAVNode> nodes = Arrays.asList(node1, node2, node3);

        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-2", selected.getId());
    }

    @Test
    void testSelectNode_WithEmptyList_ReturnsNull() {
        List<UAVNode> emptyNodes = Arrays.asList();
        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(emptyNodes, task);

        assertNull(selected);
    }

    @Test
    void testSelectNode_WithSingleNode_ReturnsThatNode() {
        UAVNode node = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node.setActiveTasksCount(new AtomicInteger(3));
        List<UAVNode> nodes = Arrays.asList(node);

        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-1", selected.getId());
    }

    @Test
    void testSelectNode_AllNodesHaveSameTasks_ReturnsFirst() {
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node1.setActiveTasksCount(new AtomicInteger(5));
        UAVNode node2 = new UAVNode("node-2", "UAV-2", 10, 100, 100);
        node2.setActiveTasksCount(new AtomicInteger(5));
        UAVNode node3 = new UAVNode("node-3", "UAV-3", 10, 100, 100);
        node3.setActiveTasksCount(new AtomicInteger(5));
        List<UAVNode> nodes = Arrays.asList(node1, node2, node3);

        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        // 当任务数相等时，返回第一个
        assertEquals("node-1", selected.getId());
    }

    @Test
    void testSelectNode_Fairness_LoadBalancing() {
        // 测试公平调度：连续选择应该轮换到不同节点
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 20, 100, 100);
        node1.setActiveTasksCount(new AtomicInteger(0));

        UAVNode node2 = new UAVNode("node-2", "UAV-2", 20, 100, 100);
        node2.setActiveTasksCount(new AtomicInteger(0));

        UAVNode node3 = new UAVNode("node-3", "UAV-3", 20, 100, 100);
        node3.setActiveTasksCount(new AtomicInteger(0));

        List<UAVNode> nodes = Arrays.asList(node1, node2, node3);
        TaskInfo task = createTask(1, 512);

        // 第一次选择：任务数都是0，返回第一个
        UAVNode first = wfqAlgorithm.selectNode(nodes, task);
        assertEquals("node-1", first.getId());
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