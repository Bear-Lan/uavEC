package com.edg.scheduler.config;

import com.edg.scheduler.model.Operator;
import com.edg.scheduler.repository.OperatorRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    // 角色权限映射：角色 -> 可访问的API路径前缀
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "ADMIN", Set.of(
                    "/api/users",       // 用户管理
                    "/api/logs",        // 操作日志
                    "/api/config",      // 系统配置
                    "/api/nodes/add",   // 添加节点
                    "/api/nodes/delete",// 删除节点
                    "/api/nodes/charge", // 节点充电
                    "/api/traffic",     // 流量控制
                    "/api/metrics/export"// 导出指标
            ),
            "OPERATOR", Set.of(
                    "/api/tasks",        // 任务管理
                    "/api/nodes",        // 节点查看
                    "/api/nodes/status", // 节点状态控制
                    "/api/nodes/position",// 节点位置
                    "/api/metrics",      // 指标查看
                    "/api/system"        // 系统状态
            )
    );

    // 无需权限检查的路径
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register"
    );

    @Autowired
    private OperatorRepository operatorRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();

        // 跳过非API请求和公开路径
        if (!path.startsWith("/api") || isPublicPath(path)) {
            return true;
        }

        // 验证Token
        String token = request.getHeader("X-Auth-Token");
        if (token == null || token.isEmpty()) {
            sendError(response, 401, "Missing authenticated token");
            return false;
        }

        Operator operator = operatorRepository.findByToken(token).orElse(null);
        if (operator == null || !operator.isEnabled()) {
            sendError(response, 401, "Invalid or expired token");
            return false;
        }

        // 检查Token过期
        if (operator.getTokenExpiryTime() > 0 && System.currentTimeMillis() > operator.getTokenExpiryTime()) {
            sendError(response, 401, "Token expired, please re-login");
            return false;
        }

        // 权限检查
        if (!hasPermission(operator.getRole(), path)) {
            sendError(response, 403, "Insufficient privileges for this operation");
            return false;
        }

        // 传递用户信息给控制器
        request.setAttribute("currentUser", operator);
        request.setAttribute("currentUserRole", operator.getRole());
        return true;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private boolean hasPermission(String role, String path) {
        // ADMIN 拥有所有权限
        if ("ADMIN".equals(role)) {
            return true;
        }

        // 获取角色的权限集合
        Set<String> permissions = ROLE_PERMISSIONS.get(role);
        if (permissions == null) {
            return false;
        }

        // 检查路径是否匹配任一权限
        return permissions.stream().anyMatch(path::startsWith);
    }

    private void sendError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}