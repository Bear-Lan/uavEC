package com.edg.scheduler.service.offloading.model;

import lombok.Data;

/**
 * 云端状态信息
 *
 * 包含云端服务器的实时状态，用于卸载决策计算：
 * - lambda: 任务到达率（任务/秒）
 * - mu: 服务率（任务/秒）
 * - queueLength: 当前排队任务数
 * - availableCpu: 云端可用CPU核心数
 * - bandwidthMbps: 到云端的可用带宽（MB/s）
 */
@Data
public class CloudStatus {

    private double lambda;  // 任务到达率（任务/秒）
    private double mu;     // 服务率（任务/秒）
    private int queueLength;
    private double availableCpu;
    private double bandwidthMbps;

    public CloudStatus() {
        this.lambda = 0.0;
        this.mu = 1.0;
        this.queueLength = 0;
        this.availableCpu = 64.0;
        this.bandwidthMbps = 50.0;
    }

    public CloudStatus(double lambda, double mu, double bandwidthMbps) {
        this.lambda = lambda;
        this.mu = mu;
        this.bandwidthMbps = bandwidthMbps;
        this.queueLength = 0;
        this.availableCpu = 64.0;
    }

    /**
     * 计算 M/M/1 排队的期望等待时间
     * 公式: W = 1 / (mu - lambda)
     *
     * @return 排队等待时间（秒）
     * @throws IllegalStateException 当 lambda >= mu 时（系统不稳定）
     */
    public double calculateQueueWaitTime() {
        if (lambda >= mu) {
            throw new IllegalStateException(
                    String.format("系统拥塞: lambda(%.2f) >= mu(%.2f), 排队模型不收敛", lambda, mu));
        }
        return 1.0 / (mu - lambda);
    }

    /**
     * 判断系统是否拥塞
     */
    public boolean isCongested() {
        return lambda >= mu * 0.8; // 到达率超过服务率的80%认为拥塞
    }

    /**
     * 获取系统利用率
     */
    public double getUtilization() {
        return lambda / mu;
    }
}