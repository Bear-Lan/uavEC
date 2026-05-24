package com.edg.scheduler;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 技术栈：
 * - Spring Boot 3.x
 * - Spring Data JPA
 * - Redisson（分布式锁、队列）
 * - WebSocket（STOMP协议）
 *
 * @author EDG Team
 * @version 1.1.0-CYBER
 */
@Slf4j
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class SchedulerApplication {

    @Autowired
    private RedissonClient redissonClient;

    public static void main(String[] args) {
        SpringApplication.run(SchedulerApplication.class, args);
    }

    @PreDestroy
    public void onShutdown() {
        log.info("=== 系统关闭中，正在清理 Redis 缓存 ===");
        try {
            // 清理任务队列
            redissonClient.getDeque("scheduler:task_queue").clear();
            log.info("已清理 scheduler:task_queue");
        } catch (Exception e) {
            log.warn("清理 scheduler:task_queue 失败: {}", e.getMessage());
        }
        try {
            // 清理活跃任务映射
            redissonClient.getMap("task:active").clear();
            log.info("已清理 task:active");
        } catch (Exception e) {
            log.warn("清理 task:active 失败: {}", e.getMessage());
        }
        try {
            // 清理云端统计
            redissonClient.getMap("cloud:stats").clear();
            log.info("已清理 cloud:stats");
        } catch (Exception e) {
            log.warn("清理 cloud:stats 失败: {}", e.getMessage());
        }
        log.info("=== Redis 缓存清理完成 ===");
    }
}
