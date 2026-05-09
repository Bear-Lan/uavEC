package com.edg.scheduler.repository;

import com.edg.scheduler.model.TaskTraceLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskTraceLogRepository extends JpaRepository<TaskTraceLog, String> {
    TaskTraceLog findByTaskId(String taskId);
    List<TaskTraceLog> findTop10ByTaskNameOrderByExecutionEndTimeDesc(String taskName);
}
