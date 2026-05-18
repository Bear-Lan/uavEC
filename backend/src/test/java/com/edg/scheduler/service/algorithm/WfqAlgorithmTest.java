package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加权公平队列调度算法单元测试
 *
 * 测试真正的 WFQ 算法：按节点权重（maxCpu）比例分配任务名额
 * 公式：deficit = (weight_i / sum(weights)) * totalActiveTasks - activeTasksCount_i
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
    void testSelectNode_EmptyList_ReturnsNull() {
        List<UAVNode> emptyNodes = Collections.emptyList();
        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(emptyNodes, task);

        assertNull(selected);
    }

    @Test
    void testSelectNode_NullList_ReturnsNull() {
        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(null, task);

        assertNull(selected);
    }

    @Test
    void testSelectNode_SingleNode_ReturnsThatNode() {
        UAVNode node = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node.setActiveTasksCount(new AtomicInteger(3));
        List<UAVNode> nodes = Collections.singletonList(node);

        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-1", selected.getId());
    }

    @Test
    void testSelectNode_EqualWeights_SelectsLeastTasks() {
        // 三个节点权重相等（maxCpu=10），选择任务数最少的
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node1.setActiveTasksCount(new AtomicInteger(5));

        UAVNode node2 = new UAVNode("node-2", "UAV-2", 10, 100, 100);
        node2.setActiveTasksCount(new AtomicInteger(2)); // 最少，应该选中

        UAVNode node3 = new UAVNode("node-3", "UAV-3", 10, 100, 100);
        node3.setActiveTasksCount(new AtomicInteger(8));

        List<UAVNode> nodes = Arrays.asList(node1, node2, node3);
        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-2", selected.getId());
    }

    @Test
    void testSelectNode_WeightBasedSelection_HighWeightLowTasks() {
        // 节点A: maxCpu=10, tasks=5
        // 节点B: maxCpu=20, tasks=5
        // 总权重=30, 总任务=15
        // 公平份额:
        //   A: (10/30)*15 = 5, deficit = 5 - 5 = 0
        //   B: (20/30)*15 = 10, deficit = 10 - 5 = 5
        // 应选择节点B (deficit最大)

        UAVNode nodeA = new UAVNode("node-A", "UAV-A", 10, 100, 100);
        nodeA.setActiveTasksCount(new AtomicInteger(5));

        UAVNode nodeB = new UAVNode("node-B", "UAV-B", 20, 100, 100);
        nodeB.setActiveTasksCount(new AtomicInteger(5));

        List<UAVNode> nodes = Arrays.asList(nodeA, nodeB);
        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-B", selected.getId());
    }

    @Test
    void testSelectNode_WeightBasedSelection_HighWeightGetsMoreTasks() {
        // 验证权重高的节点即使任务数较多也可能被选中
        // 节点A: maxCpu=10, tasks=3
        // 节点B: maxCpu=20, tasks=5
        // 总权重=30, 总任务=8
        // 公平份额:
        //   A: (10/30)*8 = 2.67, deficit = 2.67 - 3 = -0.33
        //   B: (20/30)*8 = 5.33, deficit = 5.33 - 5 = 0.33
        // 应选择节点B (deficit最大)

        UAVNode nodeA = new UAVNode("node-A", "UAV-A", 10, 100, 100);
        nodeA.setActiveTasksCount(new AtomicInteger(3));

        UAVNode nodeB = new UAVNode("node-B", "UAV-B", 20, 100, 100);
        nodeB.setActiveTasksCount(new AtomicInteger(5));

        List<UAVNode> nodes = Arrays.asList(nodeA, nodeB);
        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-B", selected.getId());
    }

    @Test
    void testSelectNode_FairShareDistribution_ThreeNodes() {
        // 验证三节点按权重比例分配
        // 节点A: maxCpu=10, tasks=2  -> fair=3, deficit=+1
        // 节点B: maxCpu=10, tasks=5  -> fair=3, deficit=-2
        // 节点C: maxCpu=20, tasks=3  -> fair=6, deficit=+3
        // 应选择节点C

        UAVNode nodeA = new UAVNode("node-A", "UAV-A", 10, 100, 100);
        nodeA.setActiveTasksCount(new AtomicInteger(2));

        UAVNode nodeB = new UAVNode("node-B", "UAV-B", 10, 100, 100);
        nodeB.setActiveTasksCount(new AtomicInteger(5));

        UAVNode nodeC = new UAVNode("node-C", "UAV-C", 20, 100, 100);
        nodeC.setActiveTasksCount(new AtomicInteger(3));

        List<UAVNode> nodes = Arrays.asList(nodeA, nodeB, nodeC);
        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-C", selected.getId());
    }

    @Test
    void testSelectNode_AllNodesAtFairShare_SelectsFirst() {
        // 所有节点都恰好在公平份额上，选择第一个
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
        assertEquals("node-1", selected.getId());
    }

    @Test
    void testSelectNode_ZeroTotalWeight_FallsBackToLeastTasks() {
        // 所有节点 maxCpu=0，退化为选择任务最少的
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 0, 0, 0);
        node1.setActiveTasksCount(new AtomicInteger(5));

        UAVNode node2 = new UAVNode("node-2", "UAV-2", 0, 0, 0);
        node2.setActiveTasksCount(new AtomicInteger(2)); // 最少

        UAVNode node3 = new UAVNode("node-3", "UAV-3", 0, 0, 0);
        node3.setActiveTasksCount(new AtomicInteger(8));

        List<UAVNode> nodes = Arrays.asList(node1, node2, node3);
        TaskInfo task = createTask(2, 512);

        UAVNode selected = wfqAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-2", selected.getId());
    }

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