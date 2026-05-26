package com.edg.scheduler.service.offloading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * DQN 神经网络权重持久化服务
 *
 * 功能：
 * - 将训练后的神经网络权重保存到文件系统（JSON格式）
 * - 系统重启后自动加载历史权重，继续学习
 * - 支持导出/导入权重快照
 *
 * 权重矩阵序列化格式：
 * - w1: [inputSize][hiddenSize]  → 输入层→隐藏层权重
 * - b1: [hiddenSize]              → 隐藏层偏置
 * - w2: [hiddenSize][outputSize]  → 隐藏层→输出层权重
 * - b2: [outputSize]              → 输出层偏置
 */
@Slf4j
@Service
public class DQNWeightPersistenceService {

    private static final String WEIGHT_DIR = "data/dqn/";
    private static final String WEIGHT_FILE = WEIGHT_DIR + "dqn_weights.json";

    private final ObjectMapper objectMapper;

    public DQNWeightPersistenceService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(WEIGHT_DIR));
        } catch (IOException e) {
            log.warn("无法创建 DQN 权重目录: {}", e.getMessage());
        }
    }

    /**
     * 保存神经网络权重到文件
     *
     * @param weights 权重数据对象
     * @return 是否保存成功
     */
    public boolean saveWeights(DQNWeights weights) {
        try {
            File file = new File(WEIGHT_FILE);
            objectMapper.writeValue(file, weights);
            log.info("DQN 权重已保存到 {}, 训练样本数: {}",
                    WEIGHT_FILE, weights.getTrainSampleCount());
            return true;
        } catch (IOException e) {
            log.error("保存 DQN 权重失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从文件加载神经网络权重
     *
     * @return 权重数据对象，如果不存在返回 null
     */
    public DQNWeights loadWeights() {
        File file = new File(WEIGHT_FILE);
        if (!file.exists()) {
            log.info("未找到历史 DQN 权重文件，将使用随机初始化");
            return null;
        }
        try {
            DQNWeights weights = objectMapper.readValue(file, DQNWeights.class);
            log.info("DQN 权重已加载: epsilon={}, 训练样本数={}, 历史训练次数={}",
                    weights.getEpsilon(), weights.getTrainSampleCount(), weights.getTotalTrainCount());
            return weights;
        } catch (IOException e) {
            log.error("加载 DQN 权重失败: {}, 使用随机初始化", e.getMessage());
            return null;
        }
    }

    /**
     * 导出权重快照到指定路径
     */
    public boolean exportWeights(String path) {
        DQNWeights current = loadWeights();
        if (current == null) {
            log.warn("没有可导出的权重");
            return false;
        }
        try {
            objectMapper.writeValue(new File(path), current);
            log.info("权重已导出到: {}", path);
            return true;
        } catch (IOException e) {
            log.error("导出权重失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从指定路径导入权重
     */
    public boolean importWeights(String path) {
        File file = new File(path);
        if (!file.exists()) {
            log.warn("导入路径不存在: {}", path);
            return false;
        }
        try {
            DQNWeights weights = objectMapper.readValue(file, DQNWeights.class);
            return saveWeights(weights);
        } catch (IOException e) {
            log.error("导入权重失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 权重数据类（用于 JSON 序列化）
     */
    public static class DQNWeights {
        private double[][] w1;
        private double[] b1;
        private double[][] w2;
        private double[] b2;
        private double epsilon;
        private int trainSampleCount;
        private int totalTrainCount;
        private long lastUpdated;

        public DQNWeights() {}

        public DQNWeights(double[][] w1, double[] b1, double[][] w2, double[] b2,
                         double epsilon, int trainSampleCount, int totalTrainCount) {
            this.w1 = w1;
            this.b1 = b1;
            this.w2 = w2;
            this.b2 = b2;
            this.epsilon = epsilon;
            this.trainSampleCount = trainSampleCount;
            this.totalTrainCount = totalTrainCount;
            this.lastUpdated = System.currentTimeMillis();
        }

        // Getters and Setters
        public double[][] getW1() { return w1; }
        public void setW1(double[][] w1) { this.w1 = w1; }

        public double[] getB1() { return b1; }
        public void setB1(double[] b1) { this.b1 = b1; }

        public double[][] getW2() { return w2; }
        public void setW2(double[][] w2) { this.w2 = w2; }

        public double[] getB2() { return b2; }
        public void setB2(double[] b2) { this.b2 = b2; }

        public double getEpsilon() { return epsilon; }
        public void setEpsilon(double epsilon) { this.epsilon = epsilon; }

        public int getTrainSampleCount() { return trainSampleCount; }
        public void setTrainSampleCount(int count) { this.trainSampleCount = count; }

        public int getTotalTrainCount() { return totalTrainCount; }
        public void setTotalTrainCount(int count) { this.totalTrainCount = count; }

        public long getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
    }
}
