package com.edg.scheduler.service.algorithm;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 地理拓扑调度算法单元测试
 * 测试 GeoAlgorithm 基于距离、CPU、电量的加权选择逻辑
 */
class GeoAlgorithmTest {

    private GeoAlgorithm geoAlgorithm;

    @BeforeEach
    void setUp() {
        geoAlgorithm = new GeoAlgorithm();
    }

    @Test
    void testGetName() {
        assertEquals("geo", geoAlgorithm.getName());
    }

    @Test
    void testSelectNode_WithEmptyList_ReturnsNull() {
        List<UAVNode> emptyNodes = Arrays.asList();
        TaskInfo task = createTask(2, 512, 50, 50);

        UAVNode selected = geoAlgorithm.selectNode(emptyNodes, task);

        assertNull(selected);
    }

    @Test
    void testSelectNode_WithSingleNode_ReturnsThatNode() {
        UAVNode node = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node.setX(50);
        node.setY(50);
        node.setBattery(100);
        node.setCurrentCpuUsage(2); // available = 8
        List<UAVNode> nodes = Arrays.asList(node);

        TaskInfo task = createTask(2, 512, 50, 50);

        UAVNode selected = geoAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-1", selected.getId());
    }

    @Test
    void testSelectNode_NearbyNodePreferred() {
        // 任务来源 (30, 30)
        // node1 在 (30, 30) 附近 - 应该被选中
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node1.setX(30);
        node1.setY(30);
        node1.setBattery(80);
        node1.setCurrentCpuUsage(2);

        // node2 距离较远 (80, 80)
        UAVNode node2 = new UAVNode("node-2", "UAV-2", 10, 100, 100);
        node2.setX(80);
        node2.setY(80);
        node2.setBattery(80);
        node2.setCurrentCpuUsage(2);

        // node3 距离中等 (50, 50)
        UAVNode node3 = new UAVNode("node-3", "UAV-3", 10, 100, 100);
        node3.setX(50);
        node3.setY(50);
        node3.setBattery(80);
        node3.setCurrentCpuUsage(2);

        List<UAVNode> nodes = Arrays.asList(node1, node2, node3);

        // 任务来源坐标 (30, 30)，node1 最近应该被选中
        TaskInfo task = createTask(2, 512, 30, 30);

        UAVNode selected = geoAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        assertEquals("node-1", selected.getId());
    }

    @Test
    void testSelectNode_LowBatteryNodePenalized() {
        // 两个节点距离相同，但 node1 电量低
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node1.setX(30);
        node1.setY(30);
        node1.setBattery(5); // 电量过低，会被惩罚
        node1.setCurrentCpuUsage(2);

        UAVNode node2 = new UAVNode("node-2", "UAV-2", 10, 100, 100);
        node2.setX(30);
        node2.setY(30);
        node2.setBattery(90);
        node2.setCurrentCpuUsage(2);

        List<UAVNode> nodes = Arrays.asList(node1, node2);

        TaskInfo task = createTask(2, 512, 30, 30);

        UAVNode selected = geoAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        // node2 电量充足，应该被选中
        assertEquals("node-2", selected.getId());
    }

    @Test
    void testSelectNode_WhenAllSame_ReturnsFirst() {
        // 所有节点条件相同
        UAVNode node1 = new UAVNode("node-1", "UAV-1", 10, 100, 100);
        node1.setX(50);
        node1.setY(50);
        node1.setBattery(80);
        node1.setCurrentCpuUsage(2);

        UAVNode node2 = new UAVNode("node-2", "UAV-2", 10, 100, 100);
        node2.setX(50);
        node2.setY(50);
        node2.setBattery(80);
        node2.setCurrentCpuUsage(2);

        UAVNode node3 = new UAVNode("node-3", "UAV-3", 10, 100, 100);
        node3.setX(50);
        node3.setY(50);
        node3.setBattery(80);
        node3.setCurrentCpuUsage(2);

        List<UAVNode> nodes = Arrays.asList(node1, node2, node3);

        TaskInfo task = createTask(2, 512, 50, 50);

        UAVNode selected = geoAlgorithm.selectNode(nodes, task);

        assertNotNull(selected);
        // 返回第一个
        assertEquals("node-1", selected.getId());
    }

    /**
     * 辅助方法：创建测试用 TaskInfo
     */
    private TaskInfo createTask(int requiredCpu, int requiredMemory, double originX, double originY) {
        TaskInfo task = new TaskInfo();
        task.setId("task-" + System.currentTimeMillis());
        task.setType("IMAGE_PROCESSING");
        task.setRequiredCpu(requiredCpu);
        task.setRequiredMemory(requiredMemory);
        task.setPriority(3);
        task.setDataSize(50);
        task.setOriginX(originX);
        task.setOriginY(originY);
        return task;
    }
}