package com.edg.scheduler.repository;

import com.edg.scheduler.model.TaskTraceLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 任务追踪日志数据访问层
 *
 * 提供任务追踪日志（TaskTraceLog）实体的数据库操作方法
 */
@Repository
public interface TaskTraceLogRepository extends JpaRepository<TaskTraceLog, String> {
    /**
     * 根据任务ID查询追踪日志
     * @param taskId 任务ID
     * @return 追踪日志（如果存在）
     */
    TaskTraceLog findByTaskId(String taskId);

    /**
     * 获取指定任务名称的最新 10 条追踪日志（按执行结束时间倒序）
     * @param taskName 任务名称
     * @return 追踪日志列表
     */
    List<TaskTraceLog> findTop10ByTaskNameOrderByExecutionEndTimeDesc(String taskName);
}
