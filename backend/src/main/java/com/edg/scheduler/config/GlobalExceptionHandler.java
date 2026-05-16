package com.edg.scheduler.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 *
 * 统一处理系统中的各类异常：
 * - Exception: 通用异常，返回500状态码
 * - IllegalArgumentException: 参数异常，返回400状态码
 * - MethodArgumentNotValidException: 参数校验异常，返回422状态码（含详细字段错误）
 *
 * 异常日志使用Slf4j记录，便于问题追踪
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理通用异常
     *
     * @param e 异常对象
     * @return 500状态的错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("GlobalExceptionHandler 捕获到未处理异常", e);
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "Internal Server Error: " + e.getMessage());
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 处理参数异常
     *
     * @param e 参数异常
     * @return 400状态的错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数异常: {}", e.getMessage());
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "Bad Request: " + e.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理参数校验异常
     *
     * @param ex 校验异常
     * @return 400状态的错误响应（包含详细字段错误）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.warn("校验错误: {}", errors);
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "Validation Failed");
        response.put("details", errors);

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }
}
