package com.edg.scheduler.controller;

import com.edg.scheduler.model.UserDTO;
import com.edg.scheduler.service.AuthService;
import com.edg.scheduler.service.UserSessionService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 认证控制器
 *
 * 处理用户认证相关操作：
 * - 用户注册：创建新用户账户（默认角色为OPERATOR）
 * - 用户登录：验证用户名密码，返回认证令牌
 * - 个人信息：获取当前用户详情
 * - 登出操作：清除用户会话
 * - 在线用户：获取当前在线用户列表
 * - 资料更新：修改用户名和位置坐标
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserSessionService userSessionService;

    /**
     * 用户注册
     *
     * 请求体参数：
     * - username: 用户名（必填）
     * - password: 密码（必填）
     * - x: 坐标X（可选，默认50）
     * - y: 坐标Y（可选，默认50）
     *
     * @param body 请求体（JSON格式）
     * @return 注册成功的用户信息（包含id、username、role）
     *         用户名已存在返回400
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        double x = ((Number) body.getOrDefault("x", 50)).doubleValue();
        double y = ((Number) body.getOrDefault("y", 50)).doubleValue();

        try {
            UserDTO user = authService.register(username, password, x, y);
            return ResponseEntity.ok(user);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 用户登录
     *
     * 请求体参数：
     * - username: 用户名
     * - password: 密码
     *
     * @param body 请求体（JSON格式）
     * @return 包含user和token的Map
     *         用户名或密码错误返回401
     *         账户被禁用返回403
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");

        try {
            Map<String, Object> result = authService.login(username, password);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 获取当前用户信息
     *
     * @param username 用户名
     * @return 用户信息（如果存在）
     *         不存在返回404
     */
    @GetMapping("/me")
    public ResponseEntity<UserDTO> getMe(@RequestParam String username) {
        return authService.getCurrentUser(username)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 用户登出
     *
     * 请求体参数：
     * - username: 用户名
     *
     * @param body 请求体（JSON格式）
     * @return 登出成功消息
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username != null && !username.isBlank()) {
            userSessionService.userLogout(username);
        }
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    /**
     * 获取当前在线用户列表
     *
     * @return 在线用户信息列表（包含用户名、坐标、登录时间）
     */
    @GetMapping("/online-users")
    public ResponseEntity<List<com.edg.scheduler.service.UserSessionService.OnlineUserInfo>> getOnlineUsers() {
        return ResponseEntity.ok(userSessionService.getOnlineUsers());
    }

    /**
     * 宣告用户上线
     *
     * 请求体参数：
     * - username: 用户名
     *
     * @param body 请求体（JSON格式）
     * @return 上线成功状态
     */
    @PostMapping("/online")
    public ResponseEntity<?> announceOnline(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username != null && !username.isBlank()) {
            userSessionService.userLogin(username);
        }
        return ResponseEntity.ok(Map.of("online", true));
    }

    @Data
    public static class UpdateProfileRequest {
        private String currentUsername;
        private String newUsername;
        private double x;
        private double y;
    }

    /**
     * 更新个人资料
     *
     * 请求体参数：
     * - currentUsername: 当前用户名
     * - newUsername: 新用户名
     * - x: 新坐标X
     * - y: 新坐标Y
     *
     * @param req 更新请求体
     * @return 更新后的用户信息
     *         当前用户不存在返回400
     *         新用户名已被占用返回400
     */
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody UpdateProfileRequest req) {
        try {
            UserDTO user = authService.updateProfile(
                    req.getCurrentUsername(),
                    req.getNewUsername(),
                    req.getX(),
                    req.getY()
            );
            return ResponseEntity.ok(user);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }
}