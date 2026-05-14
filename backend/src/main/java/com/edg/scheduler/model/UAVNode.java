package com.edg.scheduler.model;

import lombok.Data;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无人机节点实体类（内存模型，非持久化）
 *
 * 硬件配置支持异构节点类型：
 * - HEAVY_GPU: 高算力，高能耗
 * - BALANCED: 均衡型（默认）
 * - SOLAR_SCOUT: 低功耗，支持光伏充能
 * - MICRO_SENSOR: 微型传感器，低算力
 *
 * 状态说明：
 * - online: 是否在线接收调度
 * - rthMode: 返航模式（低电量自动触发）
 * - charging: 充电中
 * - manualOverride: 被用户手动锚定，忽略自动导航
 */
@Data
public class UAVNode {
    /** 节点唯一标识 */
    private String id;

    /** 节点显示名称 */
    private String name;

    /** 最大 CPU 核心数 */
    private double maxCpu;

    /** 最大内存（MB）*/
    private double maxMemory;

    /** 网络带宽（Mbps）*/
    private double networkBandwidth;

    /** 当前 CPU 使用量 */
    private double currentCpuUsage;

    /** 当前内存使用量（MB）*/
    private double currentMemoryUsage;

    /** 是否在线 */
    private boolean online;

    /** 当前活跃任务计数 */
    private AtomicInteger activeTasksCount;

    /** 坐标 X（0-100）*/
    private double x;

    /** 坐标 Y（0-100）*/
    private double y;

    /** 当前剩余电量百分比（0-100）*/
    private double battery;

    /** 返航模式（低电量自动触发）*/
    private boolean rthMode;

    /** 是否在基地充电 */
    private boolean charging;

    /** 用户手动锚定，忽略航线巡逻 */
    private boolean manualOverride;

    /** 硬件配置画像：HEAVY_GPU / BALANCED / SOLAR_SCOUT / MICRO_SENSOR */
    private String hardwareProfile;

    /** 电量消耗倍率（1.0=正常, >1.0=高耗能, <1.0=低功耗）*/
    private double drainRateMultiplier;

    /** 是否配备光伏充能面板 */
    private boolean solarHarvesting;

    /** 每周期光伏充能百分比（0 if not solar）*/
    private double solarChargeRate;

    /**
     * 构造函数（使用默认硬件配置）
     * @param id 节点ID
     * @param name 节点名称
     * @param maxCpu 最大CPU核心数
     */
    public UAVNode(String id, String name, double maxCpu) {
        this.id = id;
        this.name = name;
        this.maxCpu = maxCpu;
        this.maxMemory = maxCpu * 2048; // 每核 CPU 配置约 2GB 内存
        this.networkBandwidth = 100.0;  // 默认网卡带宽 100 Mbps
        this.currentCpuUsage = 0;
        this.currentMemoryUsage = 0;
        this.activeTasksCount = new AtomicInteger(0);
        this.online = true;

        // 随机初始化位置，满电状态
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
     * 完整参数构造函数
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
     * 拷贝构造函数，用于创建状态快照
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

    /** 获取可用 CPU 数量 */
    public double getAvailableCpu() {
        return maxCpu - currentCpuUsage;
    }

    /** 获取可用内存（MB）*/
    public double getAvailableMemory() {
        return maxMemory - currentMemoryUsage;
    }
}
