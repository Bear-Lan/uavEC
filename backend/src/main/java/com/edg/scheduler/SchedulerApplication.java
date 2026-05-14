package com.edg.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * UAV 边缘计算调度系统 - Spring Boot 启动入口
 *
 * 功能概述：
 * - 无人机节点管理与资源调度
 * - 任务分发与负载均衡
 * - 实时 WebSocket 状态推送
 * - 多算法调度策略支持（Greedy/WFQ/Geo/Custom）
 * - 操作日志与性能指标采集
 *
 * @author EDG Team
 * @version 1.1.0-CYBER
 */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class SchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerApplication.class, args);
    }
}
