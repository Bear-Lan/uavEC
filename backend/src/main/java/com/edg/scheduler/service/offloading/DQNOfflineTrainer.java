package com.edg.scheduler.service.offloading;

import com.edg.scheduler.model.TaskInfo;
import com.edg.scheduler.model.TaskTraceLog;
import com.edg.scheduler.model.UAVNode;
import com.edg.scheduler.repository.TaskRepository;
import com.edg.scheduler.repository.TaskTraceLogRepository;
import com.edg.scheduler.service.NodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DQN 离线训练服务
 *
 * 利用数据库中的真实执行记录进行离线训练：
 * - 从 task_info 表读取已完成任务的真实延迟和能耗
 * - 从 task_trace_log 表读取执行细节（排队、传输、计算延迟）
 * - 重建决策时的状态向量
 * - 用真实 reward 替代估算 reward 进行训练
 *
 * 训练流程：
 * 1. 收集数据库中已完成的历史任务
 * 2. 为每个任务构建状态向量 + 计算真实 reward
 * 3. 存入离线经验池
 * 4. 批量梯度下降训练
 * 5. 保存更新后的权重到文件
 */
@Slf4j
@Service
public class DQNOfflineTrainer {

    private static final int INPUT_SIZE = 6;
    private static final int HIDDEN_SIZE = 12;
    private static final int OUTPUT_SIZE = 3;
    private static final int BATCH_SIZE = 32;
    private static final double LEARNING_RATE = 0.005;
    private static final double GAMMA = 0.95;

    // 动作枚举
    private static final int ACTION_EDGE = 0;
    private static final int ACTION_CLOUD = 1;
    private static final int ACTION_PARTIAL = 2;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskTraceLogRepository traceLogRepository;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private DQNWeightPersistenceService weightPersistence;

    private final AtomicBoolean isTraining = new AtomicBoolean(false);
    private final AtomicInteger totalTrainCount = new AtomicInteger(0);
    private final AtomicInteger lastTrainedSampleCount = new AtomicInteger(0);

    // 经验类
    private static class Experience {
        double[] state;
        int action;
        double reward;
        String decision;

        Experience(double[] state, int action, double reward, String decision) {
            this.state = state;
            this.action = action;
            this.reward = reward;
            this.decision = decision;
        }
    }

    private final List<Experience> offlineBuffer = new ArrayList<>();

    @PostConstruct
    public void init() {
        // 应用启动时自动加载历史权重
        loadWeightsIfAvailable();
    }

    /**
     * 加载历史权重到离线训练器（如果有）
     */
    public void loadWeightsIfAvailable() {
        DQNWeightPersistenceService.DQNWeights saved = weightPersistence.loadWeights();
        if (saved != null) {
            lastTrainedSampleCount.set(saved.getTrainSampleCount());
            totalTrainCount.set(saved.getTotalTrainCount());
            log.info("离线训练器已加载历史权重: {} 个训练样本, {} 次训练",
                    saved.getTrainSampleCount(), saved.getTotalTrainCount());
        }
    }

    /**
     * 执行离线训练
     *
     * @param batchId 可选，指定批次ID进行针对性训练；为空则训练所有历史数据
     * @return 训练结果摘要
     */
    public Map<String, Object> trainOffline(String batchId) {
        if (isTraining.get()) {
            return Map.of("status", "SKIP", "message", "训练正在进行中");
        }
        if (!isTraining.compareAndSet(false, true)) {
            return Map.of("status", "SKIP", "message", "训练正在进行中");
        }

        long startTime = System.currentTimeMillis();
        offlineBuffer.clear();

        try {
            // Step 1: 收集历史任务数据
            List<TaskInfo> tasks;
            if (batchId != null && !batchId.isBlank()) {
                tasks = taskRepository.findByBatchId(batchId);
            } else {
                tasks = taskRepository.findAll().stream()
                        .filter(t -> t.getStatus() != null
                                && (t.getStatus().startsWith("COMPLETED") || t.getStatus().equals("FAILED")))
                        .toList();
            }

            if (tasks.isEmpty()) {
                return Map.of("status", "EMPTY", "message", "没有找到已完成的任务数据");
            }

            log.info("开始离线训练: {} 个任务记录", tasks.size());

            // Step 2: 为每个任务构建经验
            for (TaskInfo task : tasks) {
                Experience exp = buildExperience(task);
                if (exp != null) {
                    offlineBuffer.add(exp);
                }
            }

            if (offlineBuffer.isEmpty()) {
                return Map.of("status", "EMPTY", "message", "无法从任务数据构建有效经验（缺trace_log或节点信息）");
            }

            log.info("构建了 {} 条有效离线经验", offlineBuffer.size());

            // Step 3: 批量训练（多轮）
            int epochs = Math.max(1, offlineBuffer.size() / BATCH_SIZE);
            for (int epoch = 0; epoch < epochs; epoch++) {
                trainEpoch();
            }

            // Step 4: 保存权重
            DQNWeightPersistenceService.DQNWeights saved = weightPersistence.loadWeights();
            int prevCount = (saved != null) ? saved.getTrainSampleCount() : 0;
            int newCount = prevCount + offlineBuffer.size();

            // 这里返回保存结果，不实际保存——保存由外部触发
            totalTrainCount.incrementAndGet();
            lastTrainedSampleCount.set(newCount);

            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new HashMap<>();
            result.put("status", "SUCCESS");
            result.put("experiences", offlineBuffer.size());
            result.put("epochs", epochs);
            result.put("durationMs", duration);
            result.put("totalTrainCount", totalTrainCount.get());
            result.put("newSampleCount", offlineBuffer.size());
            result.put("message", String.format("离线训练完成: %d 条经验, %d 轮训练, 耗时 %dms",
                    offlineBuffer.size(), epochs, duration));

            log.info("离线训练完成: {} 条经验, {} 轮, 耗时 {}ms",
                    offlineBuffer.size(), epochs, duration);

            return result;

        } finally {
            isTraining.set(false);
        }
    }

    /**
     * 从单个任务构建离线经验
     */
    private Experience buildExperience(TaskInfo task) {
        // 从 trace_log 获取执行细节
        TaskTraceLog trace = traceLogRepository.findByTaskId(task.getId());
        if (trace == null) {
            return null;
        }

        // 解析当时的卸载决策
        int action = parseAction(task, trace);
        if (action < 0) {
            return null;
        }

        // 获取分配节点（边缘任务需要节点状态）
        UAVNode node = null;
        if (task.getAssignedUavId() != null && !"CLOUD-SERVER".equals(task.getAssignedUavId())) {
            node = nodeService.getNode(task.getAssignedUavId());
        }

        // 重建状态向量（归一化）
        double[] state = buildState(task, node);

        // 计算真实 reward
        double realReward = calculateRealReward(task, trace, action);

        return new Experience(state, action, realReward,
                action == ACTION_EDGE ? "EDGE" : action == ACTION_CLOUD ? "CLOUD" : "PARTIAL");
    }

    /**
     * 解析任务对应的卸载动作
     */
    private int parseAction(TaskInfo task, TaskTraceLog trace) {
        String status = task.getStatus();
        if (status == null) return -1;

        if (status.equals("RUNNING_CLOUD") || status.equals("COMPLETED_CLOUD")) {
            return ACTION_CLOUD;
        }
        if (status.equals("RUNNING_SPLIT") || status.startsWith("COMPLETED_SPLIT")) {
            return ACTION_PARTIAL;
        }
        if (status.equals("RUNNING_EDGE") || status.startsWith("COMPLETED")) {
            // 可能是EDGE或被调度算法选中的节点
            return ACTION_EDGE;
        }
        return -1;
    }

    /**
     * 构建状态向量（归一化到 [0,1]）
     *
     * 归一化范围：
     * - 数据量: [0, 1000] MB
     * - 优先级: [1, 10]
     * - 电池: [0, 100]
     * - CPU: [0, 8]
     * - 云端λ: [0, 5]
     * - 云端μ: [0.1, 5]
     */
    private double[] buildState(TaskInfo task, UAVNode node) {
        double[] state = new double[INPUT_SIZE];

        state[0] = normalize(task.getDataSize(), 0, 1000);
        state[1] = normalize(task.getPriority(), 1, 10);
        state[2] = normalize(node != null ? node.getBattery() : 100.0, 0, 100);
        state[3] = normalize(node != null ? node.getAvailableCpu() : 4.0, 0, 8);
        // 云端状态使用默认值（历史数据中无法精确还原）
        state[4] = 0.5;  // lambda = 2.5（中间值）
        state[5] = 0.5;  // mu = 2.55（中间值）

        return state;
    }

    /**
     * 计算真实 reward
     *
     * reward = - (真实总延迟/1000 + 真实总能耗/10)
     *
     * 真实总延迟 = endTime - startTime（包含排队+传输+计算+结果回传）
     * 如果 startTime/endTime 无效，则从 traceLog 累加各阶段延迟
     * 真实总能耗 = actualEnergyUsed
     */
    private double calculateRealReward(TaskInfo task, TaskTraceLog trace, int action) {
        // 方法1: 直接用任务的实际起止时间
        long realLatencyMs = 0;
        if (task.getStartTime() > 0 && task.getEndTime() > task.getStartTime()) {
            realLatencyMs = task.getEndTime() - task.getStartTime();
        } else if (trace != null) {
            // 方法2: 从 traceLog 累加各阶段延迟
            realLatencyMs = Optional.ofNullable(trace.getQueueLatency()).orElse(0L).longValue()
                    + Optional.ofNullable(trace.getTxLatency()).orElse(0L).longValue()
                    + Optional.ofNullable(trace.getComputeLatency()).orElse(0L).longValue();
        }

        // 确保延迟有效
        realLatencyMs = Math.max(1, realLatencyMs);

        // 真实能耗
        double realEnergy = Math.max(0.01, task.getActualEnergyUsed());

        // 奖励 = 延迟(秒) + 能耗/10，取负（因为是成本，越低越好）
        double reward = - (realLatencyMs / 1000.0 + realEnergy / 10.0);

        log.debug("真实reward计算: taskId={}, latency={}ms, energy={}J, reward={:.4f}",
                task.getId(), realLatencyMs, realEnergy, reward);

        return reward;
    }

    /**
     * 执行一轮训练
     */
    private void trainEpoch() {
        if (offlineBuffer.size() < BATCH_SIZE) return;

        Random rand = new Random();

        // 随机采样 batch
        List<Experience> batch = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE; i++) {
            batch.add(offlineBuffer.get(rand.nextInt(offlineBuffer.size())));
        }

        // 更新权重（简化版：直接用当前样本更新，不做目标网络分离）
        for (Experience exp : batch) {
            // 简化的梯度更新：基于 reward 信号直接更新
            // 这里使用一个非常简单的策略梯度更新
            updateWeightsSimple(exp, rand);
        }
    }

    /**
     * 简化的权重更新
     * 注意：这是离线批训练，使用历史数据的平均梯度方向
     */
    private void updateWeightsSimple(Experience exp, Random rand) {
        // 加载当前权重
        DQNWeightPersistenceService.DQNWeights weights = weightPersistence.loadWeights();
        if (weights == null) return;

        double[][] w2 = weights.getW2();
        double[] b2 = weights.getB2();
        double[][] w1 = weights.getW1();
        double[] b1 = weights.getB1();

        // 前向传播
        double[] hidden = new double[HIDDEN_SIZE];
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            double sum = b1[j];
            for (int i = 0; i < INPUT_SIZE; i++) {
                sum += exp.state[i] * w1[i][j];
            }
            hidden[j] = relu(sum);
        }

        double[] qValues = new double[OUTPUT_SIZE];
        for (int j = 0; j < OUTPUT_SIZE; j++) {
            double sum = b2[j];
            for (int i = 0; i < HIDDEN_SIZE; i++) {
                sum += hidden[i] * w2[i][j];
            }
            qValues[j] = sum;
        }

        // 计算 TD 目标（使用折扣奖励）
        double tdTarget = exp.reward;  // 离线学习只用即时奖励
        double[] targetQ = qValues.clone();
        targetQ[exp.action] = tdTarget;

        // 简化的梯度下降：推举选中的动作Q值向target靠近
        for (int j = 0; j < OUTPUT_SIZE; j++) {
            double gradient = (j == exp.action) ? (qValues[j] - targetQ[j]) : 0;
            for (int i = 0; i < HIDDEN_SIZE; i++) {
                w2[i][j] -= LEARNING_RATE * gradient * hidden[i];
            }
            b2[j] -= LEARNING_RATE * gradient;
        }

        // 更新 w1, b1（隐藏层梯度）
        for (int i = 0; i < INPUT_SIZE; i++) {
            for (int j = 0; j < HIDDEN_SIZE; j++) {
                double grad = (hidden[j] > 0) ? reluGrad(hidden[j]) *
                        w2[j][exp.action] * (qValues[exp.action] - tdTarget) *
                        exp.state[i] : 0;
                w1[i][j] -= LEARNING_RATE * grad;
            }
        }
        for (int j = 0; j < HIDDEN_SIZE; j++) {
            if (hidden[j] > 0) {
                b1[j] -= LEARNING_RATE * reluGrad(hidden[j]) *
                        w2[j][exp.action] * (qValues[exp.action] - tdTarget);
            }
        }

        // 保存更新后的权重
        weights.setW1(w1);
        weights.setB1(b1);
        weights.setW2(w2);
        weights.setB2(b2);
        weights.setLastUpdated(System.currentTimeMillis());
        weightPersistence.saveWeights(weights);
    }

    private double relu(double x) {
        return Math.max(0, x);
    }

    private double reluGrad(double x) {
        return x > 0 ? 1.0 : 0.0;
    }

    private double normalize(double value, double min, double max) {
        if (max <= min) return 0.0;
        return Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
    }

    public boolean isTraining() {
        return isTraining.get();
    }

    public int getTotalTrainCount() {
        return totalTrainCount.get();
    }

    public int getOfflineBufferSize() {
        return offlineBuffer.size();
    }
}
