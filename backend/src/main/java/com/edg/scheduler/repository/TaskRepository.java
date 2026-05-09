package com.edg.scheduler.repository;

import com.edg.scheduler.model.TaskInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<TaskInfo, String> {

    List<TaskInfo> findByAssignedUavId(String assignedUavId);

    List<TaskInfo> findByBatchId(String batchId);
}
