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

/**
 * 认证拦截器
 *
 * 功能说明：
 * - 验证请求令牌（X-Auth-Token）的有效性
 * - 检查用户角色是否有权访问特定API路径
 * - 跳过公开路径（登录、注册）
 * - 将当前用户信息传递给Controller
 *
 * 权限映射：
 * - ADMIN: 用户管理、日志查询、系统配置、节点管理、流量控制、指标导出
 * - OPERATOR: 任务管理、节点查看、指标查看、系统状态
 *
 * 无需认证的路径：/api/auth/login, /api/auth/register
 */
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
            "/api/auth/register",
            "/api/auth/logout",
            "/api/auth/online"
    );

    @Autowired
    private OperatorRepository operatorRepository;

    /**
     * 验证请求是否可放行
     *
     * 功能说明：
     * - 检查请求路径是否为公开路径
     * - 验证X-Auth-Token请求头
     * - 检查令牌有效性和过期时间
     * - 验证用户角色权限
     * - 将当前用户信息绑定到请求属性
     *
     * @param request HTTP请求
     * @param response HTTP响应
     * @param handler 处理器
     * @return 是否放行
     * @throws Exception 如果发生错误
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();

        // 跳过非API请求和公开路径
        if (!path.startsWith("/api")) {
            return true;
        }

        // 公开路径：跳过权限检查，但尝试解析用户信息供日志使用
        if (isPublicPath(path)) {
            String token = request.getHeader("X-Auth-Token");
            if (token != null && !token.isEmpty()) {
                Operator operator = operatorRepository.findByToken(token).orElse(null);
                if (operator != null && operator.isEnabled()) {
                    request.setAttribute("currentUser", operator);
                    request.setAttribute("currentUserRole", operator.getRole());
                }
            }
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

    /**
     * 检查路径是否为公开路径
     *
     * @param path 请求路径
     * @return 是否为公开路径
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * 检查角色是否有权访问路径
     *
     * @param role 用户角色
     * @param path 请求路径
     * @return 是否有权限
     */
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

    /**
     * 发送错误响应
     *
     * @param response HTTP响应
     * @param status HTTP状态码
     * @param message 错误消息
     * @throws Exception 如果发生错误
     */
    private void sendError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}