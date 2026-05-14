package com.edg.scheduler.controller;

import com.edg.scheduler.model.OperationLog;
import com.edg.scheduler.repository.OperationLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 操作日志控制器
 *
 * 提供操作日志查询 API（需 ADMIN 权限）：
 * - 支持分页查询
 * - 支持按用户名查询
 * - 支持按角色查询
 * - 支持按时间范围查询
 *
 * 日志由 OperationLogAspect 切面自动记录
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    @Autowired
    private OperationLogRepository operationLogRepository;

    @GetMapping
    public ResponseEntity<?> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) LocalDateTime startTime,
            @RequestParam(required = false) LocalDateTime endTime) {

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

        return ResponseEntity.ok(Map.of(
                "content", result.getContent(),
                "total", result.getTotalElements(),
                "pages", result.getTotalPages()
        ));
    }
}