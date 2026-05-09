package com.edg.scheduler.config;

import com.edg.scheduler.model.OperationLog;
import com.edg.scheduler.repository.OperationLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class OperationLogAspect {

    @Autowired
    private OperationLogRepository operationLogRepository;

    // 切入点：所有API控制器方法
    @Pointcut("execution(* com.edg.scheduler.controller..*.*(..))")
    public void controllerPointcut() {}

    @Around("controllerPointcut()")
    public Object logOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return joinPoint.proceed();
        }

        String path = request.getRequestURI();

        // 跳过登录注册和公开接口
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")) {
            return joinPoint.proceed();
        }

        OperationLog log = new OperationLog();
        log.setResource(path);
        log.setMethod(request.getMethod());
        log.setIpAddress(getClientIp(request));
        log.setUserAgent(request.getHeader("User-Agent"));

        // 获取当前用户
        Object user = request.getAttribute("currentUser");
        if (user != null) {
            try {
                Object username = getFieldValue(user, "getUsername");
                Object role = getFieldValue(user, "getRole");
                if (username != null) log.setUsername(username.toString());
                if (role != null) log.setRole(role.toString());
            } catch (Exception ignored) {}
        }

        // 获取请求参数
        log.setRequestParams(getRequestParams(joinPoint, request));

        // 执行目标方法并记录结果
        Object result = null;
        try {
            result = joinPoint.proceed();
            log.setResponseStatus(200);
        } catch (Exception e) {
            log.setResponseStatus(500);
            log.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        } finally {
            try {
                operationLogRepository.save(log);
            } catch (Exception ignored) {
                // 日志记录失败不应影响主业务
            }
        }

        return result;
    }

    private HttpServletRequest getRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private String getRequestParams(ProceedingJoinPoint joinPoint, HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // URL参数
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isEmpty()) {
            sb.append("query:").append(queryString);
        }

        // 方法参数（脱敏处理）
        Object[] args = joinPoint.getArgs();
        if (args != null && args.length > 0) {
            if (sb.length() > 1) sb.append(",");
            sb.append("body:[");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(",");
                Object arg = args[i];
                sb.append(sanitizeParam(arg));
            }
            sb.append("]");
        }

        sb.append("}");
        return sb.toString();
    }

    private String sanitizeParam(Object param) {
        if (param == null) return "null";

        // 密码等敏感字段脱敏
        String str = param.toString();
        if (str.length() > 100) {
            str = str.substring(0, 100) + "...";
        }
        return str;
    }

    private Object getFieldValue(Object obj, String fieldName) {
        try {
            return obj.getClass().getMethod(fieldName).invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }
}