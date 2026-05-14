package com.edg.scheduler.service;

import com.edg.scheduler.model.OperationLog;
import com.edg.scheduler.repository.OperationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 操作日志服务
 *
 * 核心职责：
 * - 日志查询：支持分页和多条件筛选
 * - 时间范围：按起止时间过滤日志
 * - 用户/角色：按用户名或角色筛选
 *
 * 事务策略：
 * - getLogs: @Transactional(readOnly=true)
 */
@Slf4j
@Service
public class LogService {

    @Autowired
    private OperationLogRepository operationLogRepository;

    /**
     * 分页查询操作日志
     * @param page 页码
     * @param size 每页大小
     * @param username 用户名过滤（可选）
     * @param role 角色过滤（可选）
     * @param startTime 开始时间（可选）
     * @param endTime 结束时间（可选）
     * @return 包含分页信息的 Map（content, total, pages）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getLogs(int page, int size, String username,
            String role, LocalDateTime startTime, LocalDateTime endTime) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createTime"));
        Page<OperationLog> result;

        if (username != null && !username.isEmpty()) {
            result = operationLogRepository.findByUsername(username, pageRequest);
        } else if (role != null && !role.isEmpty()) {
            result = operationLogRepository.findByRole(role, pageRequest);
        } else if (startTime != null && endTime != null) {
            result = operationLogRepository.findByCreateTimeBetween(startTime, endTime, pageRequest);
        } else {
            result = operationLogRepository.findAll(pageRequest);
        }

        return Map.of(
                "content", result.getContent(),
                "total", result.getTotalElements(),
                "pages", result.getTotalPages()
        );
    }
}