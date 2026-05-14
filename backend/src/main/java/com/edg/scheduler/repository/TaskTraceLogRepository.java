package com.edg.scheduler.repository;

import com.edg.scheduler.model.TaskTraceLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 任务追踪日志数据访问层
 *
 * 继承JpaRepository，提供任务追踪日志（TaskTraceLog）实体的数据库操作：
 * - findByTaskId: 根据任务ID查找追踪日志（用于任务详情展示）
 * - findTop10ByTaskNameOrderByExecutionEndTimeDesc: 获取同名任务的最近10条日志（用于历史预测）
 *
 * 追踪日志记录任务全生命周期的时间点：
 * - createdTime: 任务创建时间
 * - dequeuedTime: 出队时间
 * - executionStartTime: 执行开始时间
 * - executionEndTime: 执行结束时间
 */
@Repository
public interface TaskTraceLogRepository extends JpaRepository<TaskTraceLog, String> {
    /**
     * 根据任务ID查找追踪日志
     *
     * @param taskId 任务ID
     * @return 追踪日志（如果存在）
     */
    TaskTraceLog findByTaskId(String taskId);

    /**
     * 获取同名任务的最近10条日志（按执行结束时间降序）
     * 用于历史性能预测
     *
     * @param taskName 任务名称
     * @return 追踪日志列表
     */
    List<TaskTraceLog> findTop10ByTaskNameOrderByExecutionEndTimeDesc(String taskName);
}