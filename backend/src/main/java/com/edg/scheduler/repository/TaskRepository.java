package com.edg.scheduler.repository;

import com.edg.scheduler.model.TaskInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 任务信息数据访问层
 *
 * 提供任务（TaskInfo）实体的数据库操作方法
 */
@Repository
public interface TaskRepository extends JpaRepository<TaskInfo, String> {

    /**
     * 查询指定节点上分配的任务
     * @param assignedUavId 无人机节点ID
     * @return 该节点上的任务列表
     */
    List<TaskInfo> findByAssignedUavId(String assignedUavId);

    /**
     * 查询指定批次的任务
     * @param batchId 批次ID
     * @return 该批次的所有任务
     */
    List<TaskInfo> findByBatchId(String batchId);
}
