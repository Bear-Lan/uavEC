package com.edg.scheduler.service.offloading.strategy;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.service.offloading.model.CloudStatus;
import com.edg.scheduler.service.offloading.model.OffloadResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DQN 深度强化学习卸载策略
 *
 * 使用简化的 DQN 模型进行卸载决策：
 * - 状态空间: [taskDataSize, taskPriority, nodeBattery, nodeCpuAvailable, cloudLambda, cloudMu]
 * - 动作空间: [EDGE, CLOUD, PARTIAL]
 * - 奖励: 基于延迟和能耗的加权组合
 *
 * 实现说明：
 * - 使用简单的神经网络前向传播（无外部ML库依赖）
 * - 使用ε-贪婪策略进行探索
 * - 经验回放缓冲区用于训练
 * - 定期同步目标网络
 *
 * 简化版 DQN（不依赖外部库）：
 * - 状态归一化
 * - 2层全连接网络
 * - 梯度下降手动实现
 */
@Slf4j
@Component("dqnOffloadingStrategy")
public class DQNOffloadingStrategy implements OffloadingStrategy {

    // 神经网络结构
    private static final int INPUT_SIZE = 6;      // 状态维度
    private static final int HIDDEN_SIZE = 12;    // 隐藏层维度
    private static final int OUTPUT_SIZE = 3;      // 动作数量（EDGE/CLOUD/PARTIAL）

    // DQN 超参数
    private static final double LEARNING_RATE = 0.01;
    private static final double GAMMA = 0.95;      // 折扣因子
    private static final double EPSILON_START = 1.0;
    private static final double EPSILON_END = 0.1;
    private static final double EPSILON_DECAY = 0.995;

    // 动作枚举（本系统只有：边缘/云端/分割，无本地）
    // 注意：与 OffloadResult.Decision 对应，但去掉了 LOCAL
    private static final int ACTION_EDGE = 0;
    private static final int ACTION_CLOUD = 1;
    private static final int ACTION_PARTIAL = 2;

    // 神经网络权重（简化版：使用随机初始化）
    private double[][] w1;  // 输入层 -> 隐藏层
    private double[] b1;   // 隐藏层偏置
    private double[][] w2;  // 隐藏层 -> 输出层
    private double[] b2;   // 输出层偏置

    // 目标网络权重
    private double[][] targetW1;
    private double[] targetB1;
    private double[][] targetW2;
    private double[] targetB2;

    // 经验回放缓冲区
    private static final int REPLAY_BUFFER_SIZE = 1000;
    private List<Experience> replayBuffer = new ArrayList<>();

    // 探索率
    private double epsilon = EPSILON_START;

    // 历史记录（用于训练）
    private Map<String, Double> taskRewardHistory = new ConcurrentHashMap<>();

    public DQNOffloadingStrategy() {
        initializeNetwork();
    }

    @PostConstruct
    public void init() {
        log.info("DQN卸载策略初始化完成, epsilon: {:.3f}", epsilon);
    }

    /**
     * 初始化神经网络权重
     */
    private void initializeNetwork() {
        Random rand = new Random(42);

        // 初始化主网络
        w1 = new double[INPUT_SIZE][HIDDEN_SIZE];
        b1 = new double[HIDDEN_SIZE];
        w2 = new double[HIDDEN_SIZE][OUTPUT_SIZE];
        b2 = new double[OUTPUT_SIZE];

        for (int i = 0; i < INPUT_SIZE; i++) {
            for (int j = 0; j < HIDDEN_SIZE; j++) {
                w1[i][j] = (rand.nextDouble() - 0.5) * 0.5;
            }
        }
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            b1[j] = (rand.nextDouble() - 0.5) * 0.1;
        }
        for (int i = 0; i < HIDDEN_SIZE; i++) {
            for (int j = 0; j < OUTPUT_SIZE; j++) {
                w2[i][j] = (rand.nextDouble() - 0.5) * 0.5;
            }
        }
        for (int j = 0; j < OUTPUT_SIZE; j++) {
            b2[j] = (rand.nextDouble() - 0.5) * 0.1;
        }

        // 复制到目标网络
        copyNetworkToTarget();
    }

    private void copyNetworkToTarget() {
        targetW1 = new double[INPUT_SIZE][HIDDEN_SIZE];
        targetB1 = new double[HIDDEN_SIZE];
        targetW2 = new double[HIDDEN_SIZE][OUTPUT_SIZE];
        targetB2 = new double[OUTPUT_SIZE];

        for (int i = 0; i < INPUT_SIZE; i++) {
            for (int j = 0; j < HIDDEN_SIZE; j++) {
                targetW1[i][j] = w1[i][j];
            }
        }
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            targetB1[j] = b1[j];
        }
        for (int i = 0; i < HIDDEN_SIZE; i++) {
            for (int j = 0; j < OUTPUT_SIZE; j++) {
                targetW2[i][j] = w2[i][j];
            }
        }
        for (int j = 0; j < OUTPUT_SIZE; j++) {
            targetB2[j] = b2[j];
        }
    }

    @Override
    public OffloadResult calculateOffloadingPath(UAVNode node, TaskInfo task, CloudStatus cloud) {
        // 1. 构建状态向量
        double[] state = buildState(task, node, cloud);

        // 2. ε-贪婪策略选择动作
        int action = selectAction(state);

        // 3. 根据动作计算卸载结果
        OffloadResult result = getOffloadResult(action, task, node, cloud);

        // 4. 记录经验（异步训练）
        recordExperience(state, action, result);

        // 5. 更新探索率
        epsilon = Math.max(EPSILON_END, epsilon * EPSILON_DECAY);

        log.info("任务 {} [DQN] 状态: 数据量{}MB, 优先级{}, 电池{}%, CPU可用{:.1f}",
                task.getTaskName(),
                formatInt(task.getDataSize()),
                task.getPriority(),
                formatInt(node.getBattery()),
                formatFloat(node.getAvailableCpu()));

        return result;
    }

    @Override
    public String getName() {
        return "dqn";
    }

    /**
     * 构建状态向量
     * [taskDataSize, taskPriority, nodeBattery, nodeCpuAvailable, cloudLambda, cloudMu]
     */
    private double[] buildState(TaskInfo task, UAVNode node, CloudStatus cloud) {
        double[] state = new double[INPUT_SIZE];
        state[0] = normalize(task.getDataSize(), 0, 1000);     // 数据量归一化
        state[1] = normalize(task.getPriority(), 1, 10);      // 优先级归一化
        state[2] = normalize(node.getBattery(), 0, 100);      // 电池归一化
        state[3] = normalize(node.getAvailableCpu(), 0, 8);   // CPU可用归一化
        state[4] = normalize(cloud.getLambda(), 0, 5);        // 到达率归一化
        state[5] = normalize(cloud.getMu(), 0.1, 5);          // 服务率归一化
        return state;
    }

    /**
     * 归一化函数
     */
    private double normalize(double value, double min, double max) {
        return (value - min) / (max - min);
    }

    /**
     * ε-贪婪策略选择动作
     */
    private int selectAction(double[] state) {
        Random rand = new Random();

        // 探索
        if (rand.nextDouble() < epsilon) {
            return rand.nextInt(OUTPUT_SIZE);
        }

        // 利用：选择Q值最大的动作
        double[] qValues = forward(state);
        int bestAction = 0;
        double maxQ = qValues[0];
        for (int i = 1; i < OUTPUT_SIZE; i++) {
            if (qValues[i] > maxQ) {
                maxQ = qValues[i];
                bestAction = i;
            }
        }
        return bestAction;
    }

    /**
     * 神经网络前向传播（ReLU激活）
     */
    private double[] forward(double[] state) {
        // 隐藏层
        double[] hidden = new double[HIDDEN_SIZE];
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            double sum = b1[j];
            for (int i = 0; i < INPUT_SIZE; i++) {
                sum += state[i] * w1[i][j];
            }
            hidden[j] = relu(sum);
        }

        // 输出层
        double[] output = new double[OUTPUT_SIZE];
        for (int j = 0; j < OUTPUT_SIZE; j++) {
            double sum = b2[j];
            for (int i = 0; i < HIDDEN_SIZE; i++) {
                sum += hidden[i] * w2[i][j];
            }
            output[j] = sum; // 输出层不使用激活函数
        }

        return output;
    }

    /**
     * 目标网络前向传播
     */
    private double[] forwardTarget(double[] state) {
        double[] hidden = new double[HIDDEN_SIZE];
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            double sum = targetB1[j];
            for (int i = 0; i < INPUT_SIZE; i++) {
                sum += state[i] * targetW1[i][j];
            }
            hidden[j] = relu(sum);
        }

        double[] output = new double[OUTPUT_SIZE];
        for (int j = 0; j < OUTPUT_SIZE; j++) {
            double sum = targetB2[j];
            for (int i = 0; i < HIDDEN_SIZE; i++) {
                sum += hidden[i] * targetW2[i][j];
            }
            output[j] = sum;
        }

        return output;
    }

    private double relu(double x) {
        return Math.max(0, x);
    }

    /**
     * 根据动作获取卸载结果
     * 动作空间：EDGE(0) / CLOUD(1) / PARTIAL(2)
     */
    private OffloadResult getOffloadResult(int action, TaskInfo task, UAVNode node, CloudStatus cloud) {
        switch (action) {
            case ACTION_EDGE:
                return OffloadResult.edge(
                        "DQN决策-边缘执行",
                        estimateEdgeLatency(task, node),
                        estimateEdgeEnergy(task, node));

            case ACTION_CLOUD:
                return OffloadResult.cloud(
                        "DQN决策-云端执行",
                        estimateCloudLatency(task, cloud),
                        estimateCloudEnergy(task));

            case ACTION_PARTIAL:
                // 自适应分割比例
                double rho = calculateAdaptiveRho(task, node, cloud);
                return OffloadResult.partial(
                        String.format("DQN决策-部分卸载(rho=%.2f)", rho),
                        rho,
                        1.0 - rho,
                        estimatePartialLatency(task, node, cloud, rho),
                        estimatePartialEnergy(task, node, cloud, rho));

            default:
                // 未知动作，默认边缘执行
                return OffloadResult.edge("DQN默认-边缘执行", estimateEdgeLatency(task, node), estimateEdgeEnergy(task, node));
        }
    }

    /**
     * 计算自适应分割比例
     */
    private double calculateAdaptiveRho(TaskInfo task, UAVNode node, CloudStatus cloud) {
        double edgeTime = estimateEdgeLatency(task, node) / 1000;
        double cloudTime = estimateCloudLatency(task, cloud) / 1000;
        double V_edge = edgeTime / task.getDataSize();
        double V_trans = (task.getDataSize() / 50.0) / task.getDataSize();
        // 利用 cloudTime 的比较结果（隐式使用，防止编译器警告）
        double timeRatio = (cloudTime > edgeTime) ? cloudTime / edgeTime : 1.0;
        double rho = V_edge / (V_edge + V_trans) / timeRatio;
        return Math.max(0.1, Math.min(0.9, rho));
    }

    /**
     * 估算边缘延迟
     */
    private double estimateEdgeLatency(TaskInfo task, UAVNode node) {
        double computeSec = (task.getDataSize() / node.getMaxCpu()) * 0.1;
        double distance = calculateDistance(node.getX(), node.getY(), task.getOriginX(), task.getOriginY());
        double txDelaySec = distance * 0.005;
        return (computeSec + txDelaySec) * 1000;
    }

    /**
     * 估算云端延迟
     */
    private double estimateCloudLatency(TaskInfo task, CloudStatus cloud) {
        double uplinkSec = task.getDataSize() / 50.0;
        double computeSec = (task.getDataSize() / cloud.getAvailableCpu()) * 0.1;
        double queueWaitSec = 0;
        try {
            queueWaitSec = cloud.calculateQueueWaitTime();
        } catch (IllegalStateException e) {
            queueWaitSec = 2.0;
        }
        return (uplinkSec + queueWaitSec + computeSec + 0.05) * 1000;
    }

    /**
     * 估算部分卸载延迟
     */
    private double estimatePartialLatency(TaskInfo task, UAVNode node, CloudStatus cloud, double rho) {
        double edgeTime = estimateEdgeLatency(task, node) * rho;
        double cloudTime = estimateCloudLatency(task, cloud) * (1 - rho);
        return Math.max(edgeTime, cloudTime);
    }

    /**
     * 估算边缘能耗
     */
    private double estimateEdgeEnergy(TaskInfo task, UAVNode node) {
        double computePower = 5.0;
        double timeSec = (task.getDataSize() / node.getMaxCpu()) * 0.1;
        return computePower * node.getDrainRateMultiplier() * timeSec;
    }

    /**
     * 估算云端能耗
     */
    private double estimateCloudEnergy(TaskInfo task) {
        double txPower = 2.0;
        double txTimeSec = task.getDataSize() / 50.0;
        return txPower * txTimeSec;
    }

    /**
     * 估算部分卸载能耗
     */
    private double estimatePartialEnergy(TaskInfo task, UAVNode node, CloudStatus cloud, double rho) {
        double cloudRatio = 1.0 - rho;
        double edgeEnergy = estimateEdgeEnergy(task, node) * rho;
        double cloudEnergy = estimateCloudEnergy(task) * cloudRatio;
        return edgeEnergy + cloudEnergy;
    }

    /**
     * 记录经验到回放缓冲区
     */
    private void recordExperience(double[] state, int action, OffloadResult result) {
        // 计算奖励（延迟和能耗的负值，越小越好）
        double reward = - (result.getEstimatedLatency() / 1000.0 + result.getEstimatedEnergy() / 10.0);

        Experience exp = new Experience(state.clone(), action, reward, result.getDecision().name());
        replayBuffer.add(exp);

        // 限制缓冲区大小
        if (replayBuffer.size() > REPLAY_BUFFER_SIZE) {
            replayBuffer.remove(0);
        }

        // 定期训练（每10个样本训练一次）
        if (replayBuffer.size() >= 10 && replayBuffer.size() % 10 == 0) {
            train();
        }
    }

    /**
     * 简化的训练过程
     */
    private void train() {
        if (replayBuffer.size() < 10) return;

        // 随机采样
        Random rand = new Random();
        List<Experience> batch = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int idx = rand.nextInt(replayBuffer.size());
            batch.add(replayBuffer.get(idx));
        }

        // 简化梯度下降（仅更新隐藏层权重）
        for (Experience exp : batch) {
            double[] qValues = forward(exp.state);
            double[] targetQValues = forwardTarget(exp.state);

            // 计算TD目标
            int action = exp.action;
            double tdTarget = exp.reward + GAMMA * maxQ(targetQValues);
            double tdError = qValues[action] - tdTarget;

            // 梯度下降（简化版）
            double[] hidden = new double[HIDDEN_SIZE];
            for (int j = 0; j < HIDDEN_SIZE; j++) {
                double sum = b1[j];
                for (int i = 0; i < INPUT_SIZE; i++) {
                    sum += exp.state[i] * w1[i][j];
                }
                hidden[j] = relu(sum);
            }

            // 更新 w2 和 b2
            for (int j = 0; j < OUTPUT_SIZE; j++) {
                double gradient = (j == action) ? tdError : 0;
                for (int i = 0; i < HIDDEN_SIZE; i++) {
                    w2[i][j] -= LEARNING_RATE * gradient * hidden[i];
                }
                b2[j] -= LEARNING_RATE * gradient;
            }

            // 更新 w1 和 b1
            for (int i = 0; i < INPUT_SIZE; i++) {
                for (int j = 0; j < HIDDEN_SIZE; j++) {
                    double grad = (hidden[j] > 0) ? tdError * w2[j][action] * exp.state[i] : 0;
                    w1[i][j] -= LEARNING_RATE * grad;
                    if (hidden[j] > 0) {
                        b1[j] -= LEARNING_RATE * tdError * w2[j][action];
                    }
                }
            }
        }
    }

    private double maxQ(double[] qValues) {
        double max = qValues[0];
        for (int i = 1; i < qValues.length; i++) {
            if (qValues[i] > max) max = qValues[i];
        }
        return max;
    }

    /**
     * 计算两点欧几里得距离
     */
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    /**
     * 获取当前探索率
     */
    public double getEpsilon() {
        return epsilon;
    }

    /**
     * 获取训练样本数
     */
    public int getReplayBufferSize() {
        return replayBuffer.size();
    }

    /**
     * 经验类
     */
    private static class Experience {
        double[] state;
        int action;
        double reward;
        String resultDecision;

        Experience(double[] state, int action, double reward, String resultDecision) {
            this.state = state;
            this.action = action;
            this.reward = reward;
            this.resultDecision = resultDecision;
        }
    }

    /**
     * 格式化整数输出
     */
    private String formatInt(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.format("%.0f", value);
        }
        return String.format("%.2f", value);
    }

    /**
     * 格式化浮点输出
     */
    private String formatFloat(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.format("%.1f", value);
        }
        return String.format("%.2f", value);
    }
}