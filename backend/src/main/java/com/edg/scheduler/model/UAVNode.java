package com.edg.scheduler.model;

import lombok.Data;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无人机节点实体类
 *
 * 表示边缘计算无人机节点，包含硬件配置和运行状态：
 * - 基础信息：id, name, hardwareProfile（HEAVY_GPU/BALANCED/SOLAR_SCOUT/MICRO_SENSOR）
 * - 硬件资源：maxCpu, maxMemory, networkBandwidth, currentCpuUsage, currentMemoryUsage
 * - 位置坐标：x, y（0-100范围）
 * - 电池状态：battery（0-100%）, drainRateMultiplier, solarHarvesting, solarChargeRate
 * - 运行状态：online, rthMode（返航模式）, charging, manualOverride
 * - 任务计数：activeTasksCount（原子操作，支持高并发）
 */
@Data
public class UAVNode {
    private String id;

    private String name;

    private double maxCpu;

    private double maxMemory;

    private double networkBandwidth;

    private double currentCpuUsage;

    private double currentMemoryUsage;

    private boolean online;

    private AtomicInteger activeTasksCount;

    private double x;

    private double y;

    private double battery;

    private boolean rthMode;

    private boolean charging;

    private boolean manualOverride;

    private String hardwareProfile;

    private double drainRateMultiplier;

    private boolean solarHarvesting;

    private double solarChargeRate;

    /**
     * 构造函数（简化版）
     *
     * @param id 节点ID
     * @param name 节点名称
     * @param maxCpu 最大CPU核心数
     */
    public UAVNode(String id, String name, double maxCpu) {
        this.id = id;
        this.name = name;
        this.maxCpu = maxCpu;
        this.maxMemory = maxCpu * 2048;
        this.networkBandwidth = 100.0;
        this.currentCpuUsage = 0;
        this.currentMemoryUsage = 0;
        this.activeTasksCount = new AtomicInteger(0);
        this.online = true;

        this.x = Math.round(Math.random() * 100);
        this.y = Math.round(Math.random() * 100);
        this.battery = 100.0;
        this.rthMode = false;
        this.charging = false;
        this.manualOverride = false;
        this.hardwareProfile = "BALANCED";
        this.drainRateMultiplier = 1.0;
        this.solarHarvesting = false;
        this.solarChargeRate = 0.0;
    }

    /**
     * 构造函数（完整版）
     *
     * @param id 节点ID
     * @param name 节点名称
     * @param maxCpu 最大CPU核心数
     * @param maxMemory 最大内存
     * @param networkBandwidth 网络带宽
     */
    public UAVNode(String id, String name, double maxCpu, double maxMemory, double networkBandwidth) {
        this.id = id;
        this.name = name;
        this.maxCpu = maxCpu;
        this.maxMemory = maxMemory;
        this.networkBandwidth = networkBandwidth;
        this.currentCpuUsage = 0.0;
        this.currentMemoryUsage = 0.0;
        this.online = true;
        this.activeTasksCount = new AtomicInteger(0);
        this.rthMode = false;
        this.charging = false;
        this.manualOverride = false;
        this.hardwareProfile = "BALANCED";
        this.drainRateMultiplier = 1.0;
        this.solarHarvesting = false;
        this.solarChargeRate = 0.0;
    }

    /**
     * 拷贝构造函数
     *
     * @param other 源节点对象
     */
    public UAVNode(UAVNode other) {
        this.id = other.id;
        this.name = other.name;
        this.maxCpu = other.maxCpu;
        this.maxMemory = other.maxMemory;
        this.networkBandwidth = other.networkBandwidth;
        this.currentCpuUsage = other.currentCpuUsage;
        this.currentMemoryUsage = other.currentMemoryUsage;
        this.online = other.online;
        this.activeTasksCount = new AtomicInteger(other.activeTasksCount.get());
        this.x = other.x;
        this.y = other.y;
        this.battery = other.battery;
        this.rthMode = other.rthMode;
        this.charging = other.charging;
        this.manualOverride = other.manualOverride;
        this.hardwareProfile = other.hardwareProfile;
        this.drainRateMultiplier = other.drainRateMultiplier;
        this.solarHarvesting = other.solarHarvesting;
        this.solarChargeRate = other.solarChargeRate;
    }

    /**
     * 获取可用CPU
     *
     * @return 最大CPU减去已使用CPU
     */
    public double getAvailableCpu() {
        return maxCpu - currentCpuUsage;
    }

    /**
     * 获取可用内存
     *
     * @return 最大内存减去已使用内存
     */
    public double getAvailableMemory() {
        return maxMemory - currentMemoryUsage;
    }
}