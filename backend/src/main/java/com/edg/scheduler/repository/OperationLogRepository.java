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
 * 继承JpaRepository，提供操作日志（OperationLog）实体的数据库操作：
 * - findByUsername: 按用户名分页查询日志
 * - findByRole: 按角色分页查询日志
 * - findByCreateTimeBetween: 按时间范围查询日志
 * - findByUsernameOrderByCreateTimeDesc: 获取用户的全部历史日志（降序排列）
 *
 * 日志内容包括：
 * - 请求路径、HTTP方法、客户端IP、User-Agent
 * - 响应状态码、错误信息
 */
@Repository
public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    /**
     * 按用户名分页查询日志
     *
     * @param username 用户名
     * @param pageable 分页参数
     * @return 日志分页结果
     */
    Page<OperationLog> findByUsername(String username, Pageable pageable);

    /**
     * 按角色分页查询日志
     *
     * @param role 角色（ADMIN/OPERATOR）
     * @param pageable 分页参数
     * @return 日志分页结果
     */
    Page<OperationLog> findByRole(String role, Pageable pageable);

    /**
     * 按时间范围分页查询日志
     *
     * @param start 开始时间
     * @param end 结束时间
     * @param pageable 分页参数
     * @return 日志分页结果
     */
    Page<OperationLog> findByCreateTimeBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 获取用户的全部历史日志（按时间降序）
     *
     * @param username 用户名
     * @return 日志列表
     */
    List<OperationLog> findByUsernameOrderByCreateTimeDesc(String username);
}