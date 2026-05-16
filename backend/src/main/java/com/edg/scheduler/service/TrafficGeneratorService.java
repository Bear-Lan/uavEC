package com.edg.scheduler.service;

import com.edg.scheduler.model.TaskInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Random;

/**
 * 泊松流量生成服务
 *
 * 基于泊松过程模拟真实世界边缘网络中的突发流量：
 * - 使用 Knuth 算法生成泊松分布随机变量
 * - 每秒生成 λ 个任务（λ 为泊松分布期望值）
 * - 支持 IMAGE_PROCESSING / SENSOR_DATA / VIDEO_ANALYSIS 三种任务类型
 * - 蒙特卡洛随机采样增加环境混沌性
 */
@Slf4j
@Service
public class TrafficGeneratorService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    private boolean isGenerating = false;
    private double lambda = 5.0; // 泊松分布期望值 E(X) = λ (每秒到达的平均任务数)
    private String defaultAlgorithm = "geo";
    private final Random random = new Random();

    // The user's coordinates, simulating tasks coming from the mobile ground
    // station
    private double originX = 50.0;
    private double originY = 50.0;

    // Default weights for custom algorithm
    private double customW1 = 0.5;
    private double customW2 = 0.5;
    private double customW3 = 0.5;

    /**
     * 启动泊松流量生成
     *
     * 功能描述：
     * - 设置泊松分布参数lambda（每秒平均任务数）
     * - 设置默认调度算法
     * - 设置任务源的坐标位置
     * - 设置自定义权重（用于CustomAlgorithm）
     * - 激活流量生成开关
     * - 推送启动通知到WebSocket
     *
     * @param lambda 泊松分布期望值（每秒任务数）
     * @param algorithm 默认调度算法
     * @param originX 任务来源X坐标
     * @param originY 任务来源Y坐标
     * @param w1 距离权重
     * @param w2 CPU权重
     * @param w3 电池权重
     */
    public void startGeneration(double lambda, String algorithm, double originX, double originY, double w1, double w2,
            double w3) {
        this.lambda = lambda;
        if (algorithm != null && !algorithm.isEmpty()) {
            this.defaultAlgorithm = algorithm;
        }
        this.originX = originX;
        this.originY = originY;
        this.customW1 = w1;
        this.customW2 = w2;
        this.customW3 = w3;
        this.isGenerating = true;
        log.info("泊松流量生成已启动. Lambda = {} 任务/秒, 算法 = {}", lambda,
                this.defaultAlgorithm);
        messagingTemplate.convertAndSend("/topic/notifications",
                "{\"type\":\"TRAFFIC_GEN_STARTED\", \"message\":\"📡 启动随机流量注入, λ=" + lambda + "\"}");
    }

    /**
     * 停止泊松流量生成
     *
     * 功能描述：
     * - 关闭流量生成开关
     * - 推送停止通知到WebSocket
     */
    public void stopGeneration() {
        this.isGenerating = false;
        log.info("泊松流量生成已停止.");
        messagingTemplate.convertAndSend("/topic/notifications",
                "{\"type\":\"TRAFFIC_GEN_STOPPED\", \"message\":\"🛑 停止流量注入\"}");
    }

    /**
     * 获取流量生成状态
     *
     * @return 是否正在生成流量
     */
    public boolean isStatusGenerating() {
        return this.isGenerating;
    }

    /**
     * 获取当前lambda值
     *
     * @return 泊松分布期望值
     */
    public double getLambda() {
        return this.lambda;
    }

    /**
     * 定时生成泊松流量
     *
     * 功能描述：
     * - 每秒执行一次（@Scheduled(fixedRate = 1000)）
     * - 使用Knuth算法生成泊松分布随机数k
     * - 生成k个随机任务，每个任务使用蒙特卡洛采样设置参数
     * - 任务类型分布：60% IMAGE_PROCESSING，30% SENSOR_DATA，10% VIDEO_ANALYSIS
     * - 任务坐标在原点附近±5单位范围内随机浮动
     * - 所有任务属于同一批次（batchId）
     *
     * 任务参数采样：
     * - IMAGE_PROCESSING: CPU 0.5-1.5，数据量 20-50MB
     * - SENSOR_DATA: CPU 0.2，数据量 5-15MB
     * - VIDEO_ANALYSIS: CPU 2.0-4.0，数据量 100-500MB
     */
    @Scheduled(fixedRate = 1000)
    public void generateTrafficTick() {
        if (!isGenerating) {
            return;
        }

        // 使用 Knuth 算法生成泊松分布的随机变量 k (k >= 0)
        int k = getPoissonRandom(this.lambda);

        if (k > 0) {
            log.info("泊松Tick: 本次生成 {} 个任务...", k);
            String batchId = "POISSON-" + System.currentTimeMillis();

            for (int i = 0; i < k; i++) {
                TaskInfo task = new TaskInfo();
                task.setTaskName("AUTO-" + System.currentTimeMillis() + "-" + i);

                // 蒙特卡洛随机采样任务参数以增加环境的混沌性 (Chaos Engineering)
                int r = random.nextInt(100);
                if (r < 60) {
                    task.setType("IMAGE_PROCESSING");
                    task.setRequiredCpu(0.5 + random.nextDouble());
                    task.setDataSize((int) (20 + random.nextInt(30)));
                } else if (r < 90) {
                    task.setType("SENSOR_DATA");
                    task.setRequiredCpu(0.2);
                    task.setDataSize((int) (5 + random.nextInt(10)));
                } else {
                    task.setType("VIDEO_ANALYSIS");
                    task.setRequiredCpu(2.0 + random.nextDouble() * 2);
                    task.setDataSize((int) (100 + random.nextInt(400)));
                }

                // 模拟信源的物理发散：在原点的半径范围内随机浮动
                task.setOriginX(Math.max(0, Math.min(100, originX + (random.nextDouble() * 10 - 5))));
                task.setOriginY(Math.max(0, Math.min(100, originY + (random.nextDouble() * 10 - 5))));

                task.setRequiredMemory((int) (128 + random.nextInt(512)));
                task.setSchedulingAlgorithm(defaultAlgorithm);
                task.setCustomW1(customW1);
                task.setCustomW2(customW2);
                task.setCustomW3(customW3);
                task.setBatchId(batchId);

                taskService.submitTask(task);
            }
        }
    }

    /**
     * Knuth算法生成泊松分布随机数
     *
     * 数学原理：
     * - 泊松分布：P(X=k) = (λ^k * e^(-λ)) / k!
     * - 算法：产生均匀随机数p，累乘直到p小于e^(-λ)
     * - 返回累乘次数k即为泊松随机变量
     *
     * @param lambda 泊松分布期望值
     * @return 泊松分布随机数k（k >= 0）
     */
    private int getPoissonRandom(double lambda) {
        double L = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > L);
        return k - 1;
    }
}
