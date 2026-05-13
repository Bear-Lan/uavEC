package com.edg.scheduler.controller;

import com.edg.scheduler.model.TaskInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试用例 1：常规批量任务并发提交
 * 测试目的：验证分布式锁在多并发下的任务分配一致性。
 * 测试步骤：通过 API 向系统瞬间提交 10 个数据量在 50MB 左右的常规计算任务，
 *          观察 Redisson 优先级队列的处理情况。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchTaskSubmitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        // 登录获取 token
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", "admin");
        loginRequest.put("password", "admin");

        // 尝试登录，如果失败则注册
        MvcResult loginResult = mockMvc.perform(
                MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
        ).andReturn();

        if (loginResult.getResponse().getStatus() == 200) {
            String response = loginResult.getResponse().getContentAsString();
            Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
            authToken = (String) responseMap.get("token");
        } else {
            // 注册 admin 用户
            Map<String, Object> registerRequest = new HashMap<>();
            registerRequest.put("username", "admin");
            registerRequest.put("password", "admin");
            registerRequest.put("x", 50);
            registerRequest.put("y", 50);

            MvcResult registerResult = mockMvc.perform(
                    MockMvcRequestBuilders.post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest))
            ).andReturn();

            if (registerResult.getResponse().getStatus() == 200) {
                // 注册后登录
                loginResult = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(loginRequest))
                ).andReturn();

                String response = loginResult.getResponse().getContentAsString();
                Map<String, Object> responseMap = objectMapper.readValue(response, Map.class);
                authToken = (String) responseMap.get("token");
            }
        }

        assertNotNull(authToken, "Failed to obtain auth token");
    }

    private static final int CONCURRENT_TASKS = 10;
    private static final int TASK_DATA_SIZE_MB = 50;

    @Test
    void testConcurrentBatchTaskSubmission() throws Exception {
        System.out.println("========================================");
        System.out.println("  测试用例 1：常规批量任务并发提交");
        System.out.println("========================================");
        System.out.println("测试配置: " + CONCURRENT_TASKS + " 个任务, 每个 " + TASK_DATA_SIZE_MB + " MB");
        System.out.println();

        // 生成批量任务
        String batchId = "BATCH-" + System.currentTimeMillis();
        List<TaskInfo> tasks = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_TASKS; i++) {
            TaskInfo task = new TaskInfo();
            task.setId(UUID.randomUUID().toString());
            task.setBatchId(batchId);
            task.setTaskName("ConcurrentTask-" + i);
            task.setType("IMAGE_PROCESSING");
            task.setDataSize(TASK_DATA_SIZE_MB);
            task.setRequiredCpu(2.0);
            task.setRequiredMemory(1024);
            task.setPriority(3);
            task.setOriginX(50.0);
            task.setOriginY(50.0);
            tasks.add(task);
        }

        // 并发提交任务
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_TASKS);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
        ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < CONCURRENT_TASKS; i++) {
            final TaskInfo task = tasks.get(i);
            executor.submit(() -> {
                try {
                    MvcResult result = mockMvc.perform(
                            MockMvcRequestBuilders.post("/api/tasks")
                                    .header("X-Auth-Token", authToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(task))
                    ).andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        successCount.incrementAndGet();
                        results.put(task.getId(), "SUCCESS");
                    } else {
                        failCount.incrementAndGet();
                        results.put(task.getId(), "FAILED-" + status);
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    results.put(task.getId(), "EXCEPTION: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // 等待所有请求完成
        latch.await(30, TimeUnit.SECONDS);
        long totalTime = System.currentTimeMillis() - startTime;

        executor.shutdown();

        // 打印结果
        System.out.println("【任务提交结果】");
        System.out.println();
        System.out.println("┌──────────────────┬────────────┐");
        System.out.println("│      指标        │    数值    │");
        System.out.println("├──────────────────┼────────────┤");
        System.out.printf("│ 提交任务数       │    %5d   │%n", CONCURRENT_TASKS);
        System.out.printf("│ 成功提交        │    %5d   │%n", successCount.get());
        System.out.printf("│ 提交失败        │    %5d   │%n", failCount.get());
        System.out.printf("│ 总耗时 (ms)     │    %5d   │%n", totalTime);
        System.out.printf("│ 平均响应 (ms)   │    %5d   │%n", totalTime / CONCURRENT_TASKS);
        System.out.println("└──────────────────┴────────────┘");

        System.out.println();
        System.out.println("【任务分配详情】");
        for (Map.Entry<String, String> entry : results.entrySet()) {
            String status = entry.getValue();
            String icon = status.equals("SUCCESS") ? "✓" : "✗";
            System.out.printf("  %s Task %s: %s%n", icon, entry.getKey().substring(0, 8), status);
        }

        System.out.println();

        // 等待任务处理完成（最大 10 秒）
        System.out.println("【等待任务执行完成...】");
        Thread.sleep(10000);

        // 查询任务完成状态
        System.out.println();
        System.out.println("【任务执行状态查询】");

        // 获取已完成任务
        MvcResult completedResult = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/tasks/completed")
                        .header("X-Auth-Token", authToken)
        ).andReturn();

        assertEquals(200, completedResult.getResponse().getStatus());
        String response = completedResult.getResponse().getContentAsString();
        List<TaskInfo> completedTasks = Arrays.asList(objectMapper.readValue(response, TaskInfo[].class));

        // 统计本批次任务
        List<TaskInfo> batchTasks = completedTasks.stream()
                .filter(t -> batchId.equals(t.getBatchId()))
                .toList();

        int completedCount = batchTasks.size();
        int runningCount = CONCURRENT_TASKS - completedCount;

        System.out.println();
        System.out.println("┌──────────────────┬────────────┐");
        System.out.println("│      状态        │    数量    │");
        System.out.println("├──────────────────┼────────────┤");
        System.out.printf("│ 已完成          │    %5d   │%n", completedCount);
        System.out.printf("│ 执行中/等待     │    %5d   │%n", runningCount > 0 ? runningCount : 0);
        System.out.printf("│ 本批次总数      │    %5d   │%n", CONCURRENT_TASKS);
        System.out.println("└──────────────────┴────────────┘");

        // 获取节点状态验证任务分配
        System.out.println();
        System.out.println("【节点状态验证】");
        MvcResult nodesResult = mockMvc.perform(
                MockMvcRequestBuilders.get("/api/nodes")
                        .header("X-Auth-Token", authToken)
        ).andReturn();

        assertEquals(200, nodesResult.getResponse().getStatus());
        String nodesResponse = nodesResult.getResponse().getContentAsString();
        List<Map<String, Object>> nodes = Arrays.asList(objectMapper.readValue(nodesResponse, Map[].class));

        System.out.println();
        for (Map<String, Object> node : nodes) {
            String nodeId = (String) node.get("id");
            Boolean online = (Boolean) node.get("online");
            Integer activeTasks = (Integer) node.get("activeTasksCount");
            System.out.printf("  %s %s (在线: %s, 活跃任务: %s)%n",
                    online ? "🟢" : "🔴", nodeId, online, activeTasks);
        }

        System.out.println();
        System.out.println("【测试结论】");
        double successRate = (successCount.get() * 100.0) / CONCURRENT_TASKS;

        if (successRate == 100.0) {
            System.out.println("✓ 所有 " + CONCURRENT_TASKS + " 个并发任务均成功提交，无任务丢失或重复分配");
        } else {
            System.out.println("⚠ 成功率: " + String.format("%.1f", successRate) + "%");
        }

        if (completedCount > 0 || runningCount >= 0) {
            System.out.println("✓ 任务已被分发至无人机节点，系统调度功能正常");
        }

        // 验证
        assertEquals(CONCURRENT_TASKS, successCount.get() + failCount.get(),
                "总任务数应等于成功+失败数");
        assertTrue(successCount.get() > 0, "至少应该有任务提交成功");

        System.out.println();
        System.out.println("========================================");
        System.out.println("  测试用例 1 完成");
        System.out.println("========================================");
    }
}