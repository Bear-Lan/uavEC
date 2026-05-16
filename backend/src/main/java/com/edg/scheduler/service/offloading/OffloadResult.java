package com.edg.scheduler.service.offloading;

import lombok.Data;

/**
 * 卸载决策结果
 *
 * 包含卸载决策的各种属性：
 * - decision: LOCAL / EDGE / CLOUD / PARTIAL
 * - edgeRatio: 部分卸载时边缘处理比例（0.0-1.0）
 * - cloudRatio: 部分卸载时云端处理比例
 * - reason: 决策原因描述
 * - estimatedLatency: 预估延迟（毫秒）
 * - estimatedEnergy: 预估能耗（焦耳）
 */
@Data
public class OffloadResult {

    public enum Decision {
        LOCAL,     // 本地执行
        EDGE,      // 边缘执行
        CLOUD,     // 云端执行
        PARTIAL    // 部分卸载（边缘+云端）
    }

    private Decision decision;
    private double edgeRatio;
    private double cloudRatio;
    private String reason;
    private double estimatedLatency;
    private double estimatedEnergy;

    public static OffloadResult local(String reason, double latency, double energy) {
        OffloadResult result = new OffloadResult();
        result.setDecision(Decision.LOCAL);
        result.setReason(reason);
        result.setEstimatedLatency(latency);
        result.setEstimatedEnergy(energy);
        return result;
    }

    public static OffloadResult edge(String reason, double latency, double energy) {
        OffloadResult result = new OffloadResult();
        result.setDecision(Decision.EDGE);
        result.setReason(reason);
        result.setEstimatedLatency(latency);
        result.setEstimatedEnergy(energy);
        return result;
    }

    public static OffloadResult cloud(String reason, double latency, double energy) {
        OffloadResult result = new OffloadResult();
        result.setDecision(Decision.CLOUD);
        result.setReason(reason);
        result.setEstimatedLatency(latency);
        result.setEstimatedEnergy(energy);
        return result;
    }

    public static OffloadResult partial(String reason, double edgeRatio, double cloudRatio,
                                         double latency, double energy) {
        OffloadResult result = new OffloadResult();
        result.setDecision(Decision.PARTIAL);
        result.setEdgeRatio(edgeRatio);
        result.setCloudRatio(cloudRatio);
        result.setReason(reason);
        result.setEstimatedLatency(latency);
        result.setEstimatedEnergy(energy);
        return result;
    }

    public boolean isLocalOrEdge() {
        return decision == Decision.LOCAL || decision == Decision.EDGE;
    }

    public boolean isCloudOrPartial() {
        return decision == Decision.CLOUD || decision == Decision.PARTIAL;
    }
}