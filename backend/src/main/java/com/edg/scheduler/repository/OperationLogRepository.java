package com.edg.scheduler.repository;

import com.edg.scheduler.model.OperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志数据访问层
 *
 * 提供操作日志（OperationLog）实体的数据库操作方法
 */
@Repository
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    /**
     * 根据用户名分页查询操作日志
     * @param username 用户名
     * @param pageable 分页参数
     * @return 操作日志分页结果
     */
    Page<OperationLog> findByUsername(String username, Pageable pageable);

    /**
     * 根据角色分页查询操作日志
     * @param role 角色
     * @param pageable 分页参数
     * @return 操作日志分页结果
     */
    Page<OperationLog> findByRole(String role, Pageable pageable);

    /**
     * 根据时间范围分页查询操作日志
     * @param start 开始时间
     * @param end 结束时间
     * @param pageable 分页参数
     * @return 操作日志分页结果
     */
    Page<OperationLog> findByCreateTimeBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 根据用户名查询最新的操作日志
     * @param username 用户名
     * @return 操作日志列表（按时间倒序）
     */
    List<OperationLog> findByUsernameOrderByCreateTimeDesc(String username);
}