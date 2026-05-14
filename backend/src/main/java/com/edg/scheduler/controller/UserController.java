package com.edg.scheduler.controller;

import com.edg.scheduler.model.UserDTO;
import com.edg.scheduler.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理控制器
 *
 * 提供管理员操作接口：
 * - 用户列表：获取所有用户信息
 * - 用户状态：启用/禁用用户账户
 * - 用户删除：删除指定用户账户
 *
 * 注意：仅ADMIN角色可访问
 */
@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 获取所有用户列表
     *
     * @return 用户信息列表（包含id、username、role、enabled等）
     */
    @GetMapping
    public ResponseEntity<List<UserDTO>> listUsers() {
        return ResponseEntity.ok(userService.listAllUsers());
    }

    /**
     * 更新用户启用/禁用状态
     *
     * @param id 用户ID
     * @param enabled 是否启用
     * @return 更新后的用户信息
     *         用户不存在返回404
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateUserStatus(@PathVariable String id, @RequestParam boolean enabled) {
        try {
            UserDTO user = userService.updateUserStatus(id, enabled);
            return ResponseEntity.ok(user);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除指定用户
     *
     * @param id 用户ID
     * @return 成功返回200，用户不存在返回404
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        if (userService.deleteUser(id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}