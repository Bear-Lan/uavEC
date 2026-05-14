package com.edg.scheduler.controller;

import com.edg.scheduler.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 操作日志控制器
 *
 * 提供日志查询接口：
 * - 分页查询：支持分页和大小参数
 * - 条件筛选：按用户名、角色、时间范围过滤
 *
 * 日志记录由OperationLogAspect自动完成
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    @Autowired
    private LogService logService;

    /**
     * 分页查询操作日志
     *
     * @param page 页码（默认0）
     * @param size 每页大小（默认20）
     * @param username 用户名过滤（可选）
     * @param role 角色过滤（可选）
     * @param startTime 开始时间（可选）
     * @param endTime 结束时间（可选）
     * @return 分页结果（包含content、total、pages）
     */
    @GetMapping
    public ResponseEntity<?> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) LocalDateTime startTime,
            @RequestParam(required = false) LocalDateTime endTime) {

        return ResponseEntity.ok(logService.getLogs(page, size, username, role, startTime, endTime));
    }
}