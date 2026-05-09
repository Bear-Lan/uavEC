package com.edg.scheduler.model;

import lombok.Data;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class UAVNode {
    private String id;
    private String name;

    // 总容量限制
    private double maxCpu;
    private double maxMemory; // 兆字节 (MB)
    private double networkBandwidth; // 兆比特每秒 (Mbps)

    // 当前使用水位线
    private double currentCpuUsage;
    private double currentMemoryUsage;

    // 连接与并发状态
    private boolean online;
    private AtomicInteger activeTasksCount;

    // 阶段 3 新增物理属性
    private double x; // 坐标 X (0-100)
    private double y; // 坐标 Y (0-100)
    private double battery; // 当前剩余电量百分比 (0-100)

    // 阶段 6 高级巡航状态
    private boolean rthMode; // 自动返航状态 (Return-To-Home)
    private boolean charging; // 正在基地充电
    private boolean manualOverride; // 被用户拖拽锚定，暂时忽略导航路线

    // Phase 2: 异构硬件配置属性 (Heterogeneous Hardware Profile)
    private String hardwareProfile; // 硬件画像: "HEAVY_GPU" / "BALANCED" / "SOLAR_SCOUT" / "MICRO_SENSOR"
    private double drainRateMultiplier; // 电量消耗速率倍率 (1.0 = 正常, >1.0 = 高耗能, <1.0 = 低耗能)
    private boolean solarHarvesting; // 是否配备光伏充能面板
    private double solarChargeRate; // 每周期光伏充能量 (% per tick, 0 if not solar)

    public UAVNode(String id, String name, double maxCpu) {
        this.id = id;
        this.name = name;
        this.maxCpu = maxCpu;
        this.maxMemory = maxCpu * 2048; // 默认算法: 每核 CPU 约配置 2GB 内存
        this.networkBandwidth = 100.0; // 默认网卡带宽 100 Mbps
        this.currentCpuUsage = 0;
        this.currentMemoryUsage = 0;
        this.activeTasksCount = new AtomicInteger(0);
        this.online = true;

        // 随机在 100x100 的二维网格中撒点，并挂载满格电池
        this.x = Math.round(Math.random() * 100);
        this.y = Math.round(Math.random() * 100);
        this.battery = 100.0;
        this.rthMode = false;
        this.charging = false;
        this.manualOverride = false;
        // Default heterogeneous profile
        this.hardwareProfile = "BALANCED";
        this.drainRateMultiplier = 1.0;
        this.solarHarvesting = false;
        this.solarChargeRate = 0.0;
    }

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
        // Default heterogeneous profile
        this.hardwareProfile = "BALANCED";
        this.drainRateMultiplier = 1.0;
        this.solarHarvesting = false;
        this.solarChargeRate = 0.0;
    }

    // 拷贝构造函数，用于快照
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
        // Copy heterogeneous profile
        this.hardwareProfile = other.hardwareProfile;
        this.drainRateMultiplier = other.drainRateMultiplier;
        this.solarHarvesting = other.solarHarvesting;
        this.solarChargeRate = other.solarChargeRate;
    }

    public double getAvailableCpu() {
        return maxCpu - currentCpuUsage;
    }

    public double getAvailableMemory() {
        return maxMemory - currentMemoryUsage;
    }
}
