package com.edg.scheduler.repository;

import com.edg.scheduler.model.TaskInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 任务信息数据访问层
 *
 * 继承JpaRepository，提供任务（TaskInfo）实体的数据库操作：
 * - findByAssignedUavId: 查询分配给指定无人机的所有任务（用于节点故障恢复）
 * - findByBatchId: 查询指定批次的任务列表（用于批次指标统计）
 */
@Repository
public interface TaskRepository extends JpaRepository<TaskInfo, String> {
    /**
     * 查询分配给指定无人机的所有任务
     *
     * @param assignedUavId 无人机节点ID
     * @return 任务列表
     */
    List<TaskInfo> findByAssignedUavId(String assignedUavId);

    /**
     * 查询指定批次的任务列表
     *
     * @param batchId 批次ID
     * @return 任务列表
     */
    List<TaskInfo> findByBatchId(String batchId);
}